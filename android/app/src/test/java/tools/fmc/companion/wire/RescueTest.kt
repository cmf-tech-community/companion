package tools.fmc.companion.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/** ios RescueTests, on the same rescue-vectors.json (tools/rescue-vectors.ts, from the site's own code). */
class RescueTest {
    private val v = Vectors.rescue
    private fun h(key: String) = hex(v.getString(key))
    private fun w(talk: String, n: String) = v.getJSONObject(talk).getJSONObject("writes").getString(n)
    private fun feed(p: RescuePlan, talk: String, n: String) =
        p.notify(Uuid.CMD_NOTIFY, hex(v.getJSONObject(talk).getJSONObject("notifies").getString(n)))
    private fun hexes(l: List<RescueWrite>) = l.map { it.data.hex() }

    private fun plan(action: RescueAction = RescueAction.FactoryReset, k1: ByteArray? = h("k1"), secret: ByteArray? = null) =
        RescuePlan.of(action, k1, secret, h("random1"))!!.apply { clock = { v.getLong("now") to v.getInt("tz_seconds") } }

    /** The site's frames, in the plan's order: the time twice and the reset right behind the confirm, both again on 0x0004. */
    private fun conversation(p: RescuePlan, t: String) {
        assertEquals(listOf(w(t, "nonce")), hexes(feed(p, t, "authMac").writes))
        assertTrue(p.retry().isEmpty())
        assertEquals(listOf(w(t, "confirm"), w(t, "time"), w(t, "time"), w(t, "factoryReset")), hexes(feed(p, t, "nonceReply").writes))
        val last = feed(p, t, "confirmReply")
        assertEquals(listOf(w(t, "time"), w(t, "factoryReset")), hexes(last.writes))
        assertTrue(last.done && !last.refused)
    }

    @Test fun savedKeyMatchesSite() {
        val p = plan()
        assertEquals(listOf(w("known", "authName")), hexes(p.connected()))
        // the watch drops writes until its service is up: authName again until it answers
        assertEquals(listOf(w("known", "authName")), hexes(p.retry()))
        conversation(p, "known")
    }

    @Test fun bindsWithSecretWithoutTap() {
        val p = plan(k1 = null, secret = h("secret"))
        assertEquals(listOf(w("bind", "pair")), hexes(p.connected()))
        assertEquals(listOf(w("bind", "pair")), hexes(p.retry()))
        // the first authName after a bind opens the popup, the second is answered: two, no tap
        assertEquals(List(2) { w("bind", "authName") }, hexes(feed(p, "bind", "pairReply").writes))
        assertContentEquals(h("bound_k1"), p.k1)
        conversation(p, "bind")
    }

    @Test fun staleKeyFallsBackToSecret() {
        val failed = stockFrames(StockCmd.AUTH_FAILED, bytes(1, 2), null)[0]
        val p = plan(secret = h("secret"))
        p.connected()
        val s = p.notify(Uuid.CMD_NOTIFY, failed)
        assertTrue(s.writes.map { it.what } == listOf("pair") && !s.refused && p.k1 == null)
        val only = plan()
        only.connected()
        assertTrue(only.notify(Uuid.CMD_NOTIFY, failed).refused)
    }

    @Test fun needsAKey() = assertNull(RescuePlan.of(RescueAction.FactoryReset, null, null))

    @Test fun keepDialsMatchesSiteCodec() {
        val k: JSONObject = v.getJSONObject("keep_dials")
        val ids = k.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getLong) }
        val p = plan(RescueAction.KeepDials(ids, k.getInt("active")))
        p.connected()
        feed(p, "known", "authMac")
        val got = feed(p, "known", "nonceReply").writes.filter { it.what == "keepDials" }
        val frames = k.getJSONArray("frames").let { a -> (0 until a.length()).map(a::getString) }
        assertEquals(frames, hexes(got))
    }

    @Test fun keepsStockDials() {
        val r = rescueKeep(listOf(0xFFFF_FFFFL, 12345, 273, 280, 99999), setOf(273L, 280L))
        assertEquals(listOf(0xFFFF_FFFFL, 273L, 280L), r?.ids)
        assertEquals(1, r?.active)
        assertNull(rescueKeep(listOf(12345L), setOf(273L)))
    }
}
