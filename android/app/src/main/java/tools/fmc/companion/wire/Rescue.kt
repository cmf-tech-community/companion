package tools.fmc.companion.wire

import java.security.SecureRandom

// Rescue for a watch stuck in a boot loop on a bad face: port of WireKit Rescue.swift (fmc_frontend rescue.model.ts,
// ble.ts authenticate, fetchInfo's time and factoryReset). Pure: the BLE code calls `connected()` once notifications
// are on, `retry()` every 50 ms until the watch answers, and `notify` for every value on fff1, and writes what they
// return to fff2 at once. Everything that doesn't depend on the watch's nonce is sealed ahead.
//
// From the cmf-watch-utils emulator (stock 1.0.1.77): the watch drops every write until its own MTU exchange has
// brought the command service up (~200 ms after the connect), hence the retry; and it ignores 0x009A until a time
// (0x8004) has set its link flag. A clock more than 2 min off is applied on the second 0x8004 only, so it goes twice.
//
// Only a watch this app already holds keys for: the saved authkey (`stock-<id>`), else the pairing secret (`k-<id>`),
// with which a new bind needs no tap on the watch (the second 0x8049 is answered, cmf-watch-sdk docs/recon.md).
// Without either there is no plan: nothing is read off a watch the app never knew.

sealed class RescueAction {
    /** Gadgetbridge's FACTORY_RESET: c1 0x009A, c2 0x0001, the marker byte. The watch doesn't answer it. */
    data object FactoryReset : RescueAction()
    /** The dial-list set (`StockSession.setDials`): the watch keeps these ids it has and shows `ids[active]`. */
    data class KeepDials(val ids: List<Long>, val active: Int) : RescueAction()
}

class RescueWrite(val uuid: String, val data: ByteArray, val what: String)

class RescueStep(
    val writes: List<RescueWrite> = emptyList(),
    val note: String? = null,
    /** The action went out on an authenticated session (or the dial set was acknowledged): stop. */
    val done: Boolean = false,
    /** The watch refused every key this app has: stop and say so. */
    val refused: Boolean = false,
)

const val FACTORY_RESET = 0x009A_0001L
private val PHONE = bytes(MARKER) + "fmc".toByteArray()
private fun random16() = ByteArray(16).also(SecureRandom()::nextBytes)

class RescuePlan private constructor(
    val action: RescueAction,
    k1: ByteArray?,
    private val secret: ByteArray?,
    private var random1: ByteArray,
) {
    /** The time 0x8004 carries, (unix seconds, zone offset in seconds); a fixed one in the tests. */
    var clock: () -> Pair<Long, Int> = {
        System.currentTimeMillis() / 1000 to java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
    }
    /** The authkey in use. A bind replaces it: the caller saves it. */
    var k1: ByteArray? = k1
        private set
    private var session: ByteArray? = null
    private var phase = Phase.IDLE
    /** Bound on this connection: a refusal now is not a stale key. */
    private var bound = false
    private var parser = StockParser()
    private var nameFrames = emptyList<ByteArray>()
    private var nonceFrames = emptyList<ByteArray>()
    private var pairFrames = emptyList<ByteArray>()

    private enum class Phase { IDLE, PAIR, NAME, NONCE, CONFIRM, SENT }

    init { prepare() }

    private fun prepare() {
        secret?.let { pairFrames = stockFrames(StockCmd.AUTH_PAIR_REQ, pairRequest(random1, it), null) }
        k1?.let {
            nameFrames = stockFrames(StockCmd.AUTH_NAME, PHONE, it)
            nonceFrames = stockFrames(StockCmd.AUTH_NONCE_REQ, bytes(MARKER), it)
        }
    }

    /** The first writes on a new connection: authName with a known authkey, else the bind request. */
    fun connected(): List<RescueWrite> {
        parser = StockParser()
        session = null
        bound = false
        if (k1 != null) {
            phase = Phase.NAME
            return w(nameFrames, "authName")
        }
        phase = Phase.PAIR
        return w(pairFrames, "pair")
    }

    /** Again what the watch hasn't answered yet: it drops writes until its command service is up. */
    fun retry(): List<RescueWrite> = when (phase) {
        Phase.NAME -> w(nameFrames, "authName")
        Phase.PAIR -> w(pairFrames, "pair")
        else -> emptyList()
    }

    fun notify(uuid: String, value: ByteArray): RescueStep {
        if (!uuid.equals(Uuid.CMD_NOTIFY, ignoreCase = true)) return RescueStep()
        val got = try {
            parser.feed(value, session ?: k1)
        } catch (e: StockFailure.CannotOpen) {
            // someone confirmed the bind popup: the authkey is zero now, bind again (recon.md, "Bind popup…")
            if (e.cmd != StockCmd.AUTH_MAC || secret == null) return RescueStep()
            if (runCatching { StockParser().feed(value, ByteArray(16)) }.isFailure) return RescueStep()
            return rebind("bind popup confirmed on the watch, binding again")
        } catch (e: StockFailure) {
            return RescueStep(note = "unreadable frame ${value.copyOf(minOf(11, value.size)).hex()}")
        } ?: return RescueStep()
        val (cmd, p) = got
        return when {
            cmd == StockCmd.AUTH_FAILED && phase == Phase.NAME -> {
                // the saved authkey is stale (Nothing X bound again since): bind with the secret, on this connection
                if (secret == null || bound) return RescueStep(note = "A061: the watch refused the saved key", refused = true)
                k1 = null
                rebind("A061: saved key refused, binding with the pairing secret")
            }
            cmd == StockCmd.AUTH_FAILED && phase == Phase.PAIR ->
                RescueStep(note = "A061: the watch refused the pairing secret", refused = true)
            cmd == StockCmd.AUTH_PAIR_REP && phase == Phase.PAIR -> {
                val key = secret?.let { pairKey(random1, p, it) }
                    ?: return RescueStep(note = "0048: watch signature mismatch", refused = true)
                k1 = key
                bound = true
                prepare()
                phase = Phase.NAME
                // the first 0x8049 after a bind only opens the popup; the second is answered, no tap needed
                RescueStep(w(nameFrames, "authName") + w(nameFrames, "authName"), "0048: bound")
            }
            cmd == StockCmd.AUTH_MAC && phase == Phase.NAME -> {
                phase = Phase.NONCE
                RescueStep(w(nonceFrames, "nonce"), "0049")
            }
            cmd == StockCmd.AUTH_NONCE_REP && phase == Phase.NONCE -> {
                val s = sessionKey(p, k1 ?: return RescueStep())
                session = s
                phase = Phase.CONFIRM
                // the time and the action ride right behind the confirm: commands are handled in order
                RescueStep(w(stockFrames(StockCmd.AUTH_CONFIRM_REQ, bytes(MARKER), s), "confirm") + timeWrites(s) +
                    timeWrites(s) + actionWrites(s), "004C")
            }
            cmd == StockCmd.AUTH_CONFIRM_REP && phase == Phase.CONFIRM -> {
                phase = Phase.SENT
                // again on the confirmed session, in case the early one was dropped; a reset that lands twice is one reset
                RescueStep(timeWrites(session!!) + actionWrites(session!!), "0004: authenticated", done = action == RescueAction.FactoryReset)
            }
            cmd == StockCmd.WF_INSTALLED && (phase == Phase.SENT || phase == Phase.CONFIRM) ->
                RescueStep(note = "A055 ${p.hex()}", done = true)
            else -> RescueStep(note = "%04X".format(cmd and 0xFFFF))
        }
    }

    private fun rebind(note: String): RescueStep {
        random1 = random16()
        prepare()
        phase = Phase.PAIR
        return RescueStep(w(pairFrames, "pair"), note)
    }

    private fun timeWrites(s: ByteArray): List<RescueWrite> {
        val (now, tz) = clock()
        return w(stockFrames(StockCmd.TIME, stockTime(now, tz), s), "time")
    }

    private fun actionWrites(s: ByteArray): List<RescueWrite> = when (action) {
        RescueAction.FactoryReset -> w(stockFrames(FACTORY_RESET, bytes(MARKER), s), "factoryReset")
        is RescueAction.KeepDials -> {
            val p = buf {
                u8(1); u8(action.active); u8(action.ids.size); u8(0)
                action.ids.forEach { le32(it) }
            }
            w(stockFrames(StockCmd.WF_LIST, p, s), "keepDials")
        }
    }

    private fun w(frames: List<ByteArray>, what: String) = frames.map { RescueWrite(Uuid.CMD_WRITE, it, what) }

    companion object {
        /** null when there is nothing to authenticate with. */
        fun of(action: RescueAction, k1: ByteArray?, secret: ByteArray?, random1: ByteArray = random16()): RescuePlan? {
            val key = k1?.takeIf { it.size == 16 }
            val sec = secret?.takeIf { it.size == 16 }
            if (key == null && sec == null) return null
            return RescuePlan(action, key, sec, random1)
        }
    }
}

/** ble.ts fetchInfo's 0x8004: unix seconds ‖ the zone's offset in ms, big endian. */
fun stockTime(now: Long, tzSeconds: Int) = buf { be32(now); be32(tzSeconds * 1000L and 0xFFFF_FFFFL) }

/** The dials to keep for [RescueAction.KeepDials]: of the list the watch last reported, the stock ones (and the photo
 * face's FFFFFFFF), in order; the first stock one is shown. null when that leaves no stock dial. */
fun rescueKeep(listed: List<Long>, stock: Set<Long>): RescueAction.KeepDials? {
    val ids = listed.filter { it in stock || it == 0xFFFF_FFFFL }
    val active = ids.indexOfFirst { it != 0xFFFF_FFFFL }
    return if (active < 0) null else RescueAction.KeepDials(ids, active)
}
