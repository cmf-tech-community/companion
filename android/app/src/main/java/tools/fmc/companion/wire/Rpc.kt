package tools.fmc.companion.wire

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

// Miniapp services RPC (docs/miniapp-rpc.md): requests from a watch app, replies from the phone's services.
// Byte layouts are fixed by rpc-vectors.json, shared with iOS. Everything here is pure but `answer`, which runs a
// service through `Phone`.

object Method {
    const val CAPS = 0x01
    const val CALENDAR = 0x10
    const val REMINDERS = 0x11
    const val REMINDER_DONE = 0x12
    const val LOCATION = 0x20
    const val WEATHER = 0x30
    const val HTTP = 0x40
    const val HTTP_REQUEST = 0x41
    const val NOTIFICATIONS = 0x50
    const val STORAGE_GET = 0x60
    const val STORAGE_PUT = 0x61
    const val STORAGE_DELETE = 0x62
    const val BATTERY = 0x70
    const val NOTIFY = 0x51
    const val MEDIA = 0x80
    const val MEDIA_CONTROL = 0x81
    const val JOB_SET = 0x90
    const val JOB_DELETE = 0x91

    /** No cap: in `caps` always, after the others. */
    val FREE = listOf(STORAGE_GET, STORAGE_PUT, STORAGE_DELETE, BATTERY, JOB_SET, JOB_DELETE)

    /** Methods whose request comes in parts (`index ‖ total ‖ chunk`). */
    val MULTI_PART = setOf(HTTP_REQUEST, STORAGE_PUT, JOB_SET)
}

/** What the phone keeps between requests: the parts of a request not joined yet, and each package's store. */
class RpcState(val storage: KeyValueStore) {
    val parts = RequestParts()
    var now: () -> Long = System::currentTimeMillis
    private val notified = mutableMapOf<String, Long>()

    /** notify: at most one per 10 s per package; true takes that one. */
    @Synchronized fun mayNotify(pkg: String): Boolean {
        notified[pkg]?.let { if (now() - it < 10_000) return false }
        notified[pkg] = now()
        return true
    }
}

object RpcStatus {
    const val OK = 0
    const val BAD_REQUEST = 1
    const val DENIED = 2
    const val UNAVAILABLE = 3
    const val ASK = 4
    const val TOO_LARGE = 5
}

/** A service's answer other than OK. */
class RpcError(val status: Int) : Exception("rpc status $status")

/** `method` null: an `R1Q` with an id the phone can't read further, answered `BAD_REQUEST`. */
class Request(val id: Int, val method: Int?, val args: ByteArray)

/** null: not a request (another prefix, `R1` ‖ other kind, or no id byte): dropped. */
fun parseRequest(m: ByteArray): Request? {
    if (m.size < 4 || m[0] != 'R'.code.toByte() || m[1] != '1'.code.toByte() || m[2] != 'Q'.code.toByte()) return null
    if (m.size < 5) return Request(m.u8(3), null, ByteArray(0))
    return Request(m.u8(3), m.u8(4), m.copyOfRange(5, m.size))
}

const val RECORD_MAX = 121

/** One message per record; none or an error: one message, total 0. */
fun replies(id: Int, status: Int, records: List<ByteArray> = emptyList()): List<ByteArray> {
    fun msg(index: Int, total: Int, record: ByteArray) =
        buf { add("R1A".toByteArray()); u8(id); u8(status); u8(index); u8(total); add(record) }
    if (status != RpcStatus.OK || records.isEmpty()) return listOf(msg(0, 0, ByteArray(0)))
    return records.mapIndexed { i, r -> msg(i, records.size, r) }
}

/** UTF-8 of `s`, at most `max` bytes, cut before a character that does not fit. */
fun utf8Cut(s: String, max: Int): ByteArray {
    val b = s.toByteArray()
    if (b.size <= max) return b
    var n = max
    while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
    return b.copyOf(n)
}

fun rgb565(argb: Int): Int = ((argb shr 19 and 0x1F) shl 11) or ((argb shr 10 and 0x3F) shl 5) or (argb shr 3 and 0x1F)

class Event(
    val day: Int, val start: Int, val duration: Int, val allDay: Boolean, val tentative: Boolean,
    val color: Int, val title: String,
)

/** (day from today, minute of the day, real minutes to the end) of a timed event, in `now`'s zone. */
fun eventTimes(now: ZonedDateTime, start: Instant, end: Instant): Triple<Int, Int, Int> {
    val s = start.atZone(now.zone)
    val day = ChronoUnit.DAYS.between(now.toLocalDate(), s.toLocalDate()).toInt()
    return Triple(day, s.hour * 60 + s.minute, Duration.between(start, end).toMinutes().toInt())
}

fun calendarRecord(e: Event, show: Boolean): ByteArray = buf {
    u8(e.day); le16(e.start); le16(e.duration)
    u8((if (e.allDay) 1 else 0) or (if (e.tentative) 2 else 0) or (if (show) 0 else 4))
    le16(e.color)
    if (show) add(utf8Cut(e.title, 64))
}

/** FNV-1a 32 of the id's UTF-8: the watch's handle for a reminder (iOS; kept here for the shared vectors). */
fun shortId(id: String): Long = id.toByteArray().fold(0x811C9DC5L) { h, b -> ((h xor (b.toLong() and 0xFF)) * 0x01000193L) and 0xFFFFFFFFL }

/** `id:u32 ‖ day:i8 ‖ minute:u16 ‖ flags:u8 ‖ color:u16 ‖ title`; flags bit 0 due date, bit 1 due time, bit 2 hidden. */
fun reminderRecord(id: String, day: Int?, minute: Int?, color: Int, title: String, show: Boolean): ByteArray = buf {
    le32(shortId(id)); u8(day ?: 0); le16(minute ?: 0)
    u8((if (day != null) 1 else 0) or (if (minute != null) 2 else 0) or (if (show) 0 else 4))
    le16(color)
    if (show) add(utf8Cut(title, 64))
}

/** 0.1° rounded; the age in minutes, 0xFFFF for older. */
fun locationRecord(lat: Double, lon: Double, ageMin: Long, place: String): ByteArray = buf {
    le16((lat * 10).roundToInt()); le16((lon * 10).roundToInt()); le16(ageMin.coerceIn(0, 0xFFFF).toInt())
    add(utf8Cut(place, 32))
}

fun weatherNow(temp: Double, code: Int, wind: Double, humidity: Int, ageMin: Long): ByteArray = buf {
    u8(0); le16((temp * 10).roundToInt()); u8(code); u8(wind.roundToInt().coerceIn(0, 255))
    u8(humidity.coerceIn(0, 100)); u8(ageMin.coerceIn(0, 255).toInt())
}

fun weatherDay(day: Int, min: Double, max: Double, code: Int, precip: Int): ByteArray = buf {
    u8(1); u8(day); u8(min.roundToInt()); u8(max.roundToInt()); u8(code); u8(precip.coerceIn(0, 100))
}

class WeatherHour(val temp: Double, val code: Int, val precip: Int, val night: Boolean)

/** `days [‖ hours]`: hours 0–24, absent is 0. null for anything else. */
fun weatherArgs(a: ByteArray): Pair<Int, Int>? = when {
    a.size == 1 -> a.u8(0) to 0
    a.size == 2 && a.u8(1) <= 24 -> a.u8(0) to a.u8(1)
    else -> null
}

/** `2 ‖ hour ‖ n × (temp:i8 ‖ code ‖ precip)`, at most [limit] hours and never over 24; precip's bit 7 is night.
 *  [hour] is the local hour (0–23) of the first entry. null when that leaves no hour. */
fun weatherHours(hour: Int, hours: List<WeatherHour>, limit: Int): ByteArray? {
    val h = hours.take(limit.coerceIn(0, 24))
    if (h.isEmpty()) return null
    return buf {
        u8(2); u8(hour)
        for (x in h) { u8(x.temp.roundToInt().coerceIn(-128, 127)); u8(x.code); u8(x.precip.coerceIn(0, 100) or if (x.night) 0x80 else 0) }
    }
}

const val HTTP_BODY_MAX = 2048

/** `code ‖ length`, then the body in record-sized chunks; `TOO_LARGE` over 2048 bytes. */
fun httpRecords(code: Int, body: ByteArray): List<ByteArray> {
    if (body.size > HTTP_BODY_MAX) throw RpcError(RpcStatus.TOO_LARGE)
    val chunks = (body.indices step RECORD_MAX).map { body.copyOfRange(it, minOf(it + RECORD_MAX, body.size)) }
    return listOf(buf { le16(code); le16(body.size) }) + chunks
}

/** A watch app's notification on the phone. `find`: "find my phone", as loud as the phone allows. */
data class PhoneAlert(val title: String, val body: String, val sound: Boolean, val find: Boolean)

/** `flags:u8 ‖ title_len:u8 ‖ title ‖ body`, flags bit 0 sound, bit 1 find my phone; title ≤ 64 bytes, both UTF-8. */
fun parseAlert(d: ByteArray): PhoneAlert? {
    if (d.size < 2 || d.u8(1) > 64 || d.size < 2 + d.u8(1)) return null
    fun utf8(b: ByteArray) = runCatching {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
    }.getOrNull()
    val title = utf8(d.copyOfRange(2, 2 + d.u8(1))) ?: return null
    val body = utf8(d.copyOfRange(2 + d.u8(1), d.size)) ?: return null
    return PhoneAlert(title, body, d.u8(0) and 1 != 0, d.u8(0) and 2 != 0)
}

/** media.control's action. */
object MediaAction {
    const val PLAY = 0
    const val PAUSE = 1
    const val TOGGLE = 2
    const val NEXT = 3
    const val PREVIOUS = 4
}

/** What plays on the phone. Seconds; null unknown. */
data class NowPlaying(val playing: Boolean, val position: Long?, val duration: Long?, val app: String, val title: String, val artist: String)

/** `flags:u8 ‖ position:u16 ‖ duration:u16 ‖ app_len:u8 ‖ app ‖ title_len:u8 ‖ title ‖ artist`; flags bit 0 playing,
 *  seconds with 0xFFFF unknown, app ≤ 24, title ≤ 48, artist ≤ 32 bytes. */
fun mediaRecord(n: NowPlaying): ByteArray {
    val app = utf8Cut(n.app, 24)
    val title = utf8Cut(n.title, 48)
    return buf {
        u8(if (n.playing) 1 else 0)
        le16(n.position?.coerceIn(0, 0xFFFE)?.toInt() ?: 0xFFFF); le16(n.duration?.coerceIn(0, 0xFFFE)?.toInt() ?: 0xFFFF)
        u8(app.size); add(app); u8(title.size); add(title); add(utf8Cut(n.artist, 32))
    }
}

/** `level:u8 ‖ flags:u8`, level 0..100, flags bit 0 plugged in. */
fun batteryRecord(level: Int, charging: Boolean): ByteArray = bytes(level.coerceIn(0, 100), if (charging) 1 else 0)

fun notificationRecord(ageMin: Long, app: String, text: String, show: Boolean): ByteArray {
    val label = utf8Cut(app, 24)
    return buf {
        le16(ageMin.coerceIn(0, 0xFFFF).toInt()); u8(if (show) 0 else 4); u8(label.size); add(label)
        if (show) add(utf8Cut(text, 64))
    }
}

/** The lowercased host of an `https://` URL of at most `max` bytes; null for anything else. */
fun httpHost(args: ByteArray, max: Int = 123): String? {
    if (args.isEmpty() || args.size > max) return null
    val uri = runCatching { URI(String(args)) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https") return null
    return uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** One `http.request` (0x41), or `http.get` as GET with no body. `host` lowercased: the cap is `http:<host>`. */
class HttpCall(val method: String, val url: String, val host: String, val body: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?) = other is HttpCall && method == other.method && url == other.url && body.contentEquals(other.body)
    override fun hashCode() = url.hashCode()
    override fun toString() = "$method $url ${body.size} B"

    companion object {
        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
    }
}

/** The joined `http.request` payload, `method ‖ url_len ‖ url ‖ body`; null: unknown method, not https, a url past
 *  the end, GET with a body. */
fun parseHttpCall(p: ByteArray): HttpCall? {
    if (p.size < 2 || p.u8(0) >= HttpCall.METHODS.size || p.size < 2 + p.u8(1)) return null
    val urlBytes = p.copyOfRange(2, 2 + p.u8(1))
    val host = httpHost(urlBytes, max = 255) ?: return null
    val body = p.copyOfRange(2 + p.u8(1), p.size)
    if (p.u8(0) == 0 && body.isNotEmpty()) return null
    return HttpCall(HttpCall.METHODS[p.u8(0)], String(urlBytes), host, body)
}

/** The user's headers (name to value), plus `Content-Type: application/json` for a body that starts with `{` or `[`
 *  when they set none. */
fun requestHeaders(user: List<Pair<String, String>>, body: ByteArray): List<Pair<String, String>> {
    val named = user.filter { it.first.isNotBlank() }
    val json = body.firstOrNull()?.let { it == '{'.code.toByte() || it == '['.code.toByte() } == true
    if (!json || named.any { it.first.equals("content-type", ignoreCase = true) }) return named
    return named + ("Content-Type" to "application/json")
}

/** Joins the parts of `http.request` (`index ‖ total ‖ chunk`), one open request per package. */
class RequestParts {
    private class Open(val id: Int, val method: Int, val total: Int, val next: Int, val data: ByteArray)
    private val open = mutableMapOf<String, Open>()

    /** The joined payload after the last part, null before it. `BAD_REQUEST` for a part out of order, a new id
     *  (or another multi-part method) before the last part, or total outside 1..9; the open request is dropped then. */
    @Synchronized fun add(pkg: String, id: Int, args: ByteArray, method: Int = Method.HTTP_REQUEST): ByteArray? {
        val prev = open.remove(pkg)
        if (args.size < 2 || args.size - 2 > RECORD_MAX || args.u8(1) !in 1..9) throw RpcError(RpcStatus.BAD_REQUEST)
        val index = args.u8(0)
        val total = args.u8(1)
        val chunk = args.copyOfRange(2, args.size)
        val data = if (prev != null) {
            if (prev.id != id || prev.method != method || prev.total != total || index != prev.next) throw RpcError(RpcStatus.BAD_REQUEST)
            prev.data + chunk
        } else {
            if (index != 0) throw RpcError(RpcStatus.BAD_REQUEST)
            chunk
        }
        if (index == total - 1) return data
        open[pkg] = Open(id, method, total, index + 1, data)
        return null
    }
}

/** The cap a request needs; null: unknown method or args it can't take. */
fun capOf(method: Int, args: ByteArray): String? = when (method) {
    Method.CALENDAR -> "calendar".takeIf { args.size == 3 }
    Method.LOCATION -> "location"
    Method.WEATHER -> "weather".takeIf { weatherArgs(args) != null }
    Method.HTTP -> httpHost(args)?.let { "http:$it" }
    Method.HTTP_REQUEST -> parseHttpCall(args)?.let { "http:${it.host}" }
    Method.NOTIFICATIONS -> "notifications".takeIf { args.size == 1 }
    Method.BATTERY -> "" // no cap
    Method.NOTIFY -> parseAlert(args)?.let { "notify" }
    Method.MEDIA -> "media"
    Method.JOB_SET -> parseJob(args)?.cap
    Method.MEDIA_CONTROL -> "media".takeIf { args.size == 1 && args.u8(0) <= MediaAction.PREVIOUS }
    else -> null
}

/** What the dispatcher needs from the phone: the grants, the OS permissions and the services. */
interface Phone {
    /** cap → allowed (true) or denied (false); a cap with no answer yet is missing. */
    fun grants(pkg: String): Map<String, Boolean>
    fun osAllows(cap: String): Boolean

    /** Keeps the pending request and tells the user, at most once a day per (pkg, cap). `write`: http.request, which
     *  can change data on the host (the ask says so). */
    fun ask(pkg: String, cap: String, write: Boolean)

    /** Parts, stores: one per phone. */
    val state: RpcState

    /** job.set: keeps it (replacing the package's job with that id) for the scheduler. */
    fun saveJob(pkg: String, job: RefreshJob)
    fun deleteJob(pkg: String, id: Int)

    /** The records of a granted call; `RpcError` for any other status. For http.request `args` is the joined
     *  payload. */
    suspend fun call(pkg: String, method: Int, args: ByteArray): List<ByteArray>
}

/** The reply messages to one message of `pkg`; null: not a request, nothing to send. */
suspend fun answer(pkg: String, msg: ByteArray, phone: Phone, budgetMs: Long = 7000): List<ByteArray>? {
    val r = parseRequest(msg) ?: return null
    val method = r.method ?: return replies(r.id, RpcStatus.BAD_REQUEST)
    val grants = phone.grants(pkg)
    fun ok(cap: String) = grants[cap] == true && phone.osAllows(cap)
    if (method == Method.CAPS) {
        val fixed = listOf(Method.CALENDAR to "calendar", Method.LOCATION to "location", Method.WEATHER to "weather",
            Method.NOTIFICATIONS to "notifications", Method.NOTIFY to "notify", Method.MEDIA to "media",
            Method.MEDIA_CONTROL to "media").filter { ok(it.second) }.map { it.first }
        val http = if (grants.any { it.key.startsWith("http:") && it.value }) listOf(Method.HTTP, Method.HTTP_REQUEST) else emptyList()
        return replies(r.id, RpcStatus.OK, listOf(((fixed + http).sorted() + Method.FREE).map { it.toByte() }.toByteArray()))
    }
    // multi-part: no reply before the last part, then the joined payload stands for the args
    val args = if (method !in Method.MULTI_PART) r.args else try {
        phone.state.parts.add(pkg, r.id, r.args, method) ?: return emptyList()
    } catch (e: RpcError) {
        return replies(r.id, e.status)
    }
    // no cap: the phone's own store
    if (method in Method.STORAGE_GET..Method.STORAGE_DELETE) return try {
        replies(r.id, RpcStatus.OK, storage(phone.state.storage, pkg, method, args))
    } catch (e: RpcError) {
        replies(r.id, e.status)
    }
    if (method == Method.JOB_DELETE) {
        if (args.size != 1) return replies(r.id, RpcStatus.BAD_REQUEST)
        phone.deleteJob(pkg, args.u8(0))
        return replies(r.id, RpcStatus.OK)
    }
    // iOS only: Android has no reminders API for other apps (docs/miniapp-rpc.md)
    if (method == Method.REMINDERS || method == Method.REMINDER_DONE) return replies(r.id, RpcStatus.UNAVAILABLE)
    val cap = capOf(method, args) ?: return replies(r.id, RpcStatus.BAD_REQUEST)
    if (cap.isNotEmpty()) when (grants[cap]) {
        null -> { phone.ask(pkg, cap, method == Method.HTTP_REQUEST); return replies(r.id, RpcStatus.ASK) }
        false -> return replies(r.id, RpcStatus.DENIED)
        true -> if (!phone.osAllows(cap)) return replies(r.id, RpcStatus.DENIED)
    }
    if (method == Method.JOB_SET) { phone.saveJob(pkg, parseJob(args)!!); return replies(r.id, RpcStatus.OK) }
    if (method == Method.NOTIFY && !phone.state.mayNotify(pkg)) return replies(r.id, RpcStatus.UNAVAILABLE)
    // not a child of the caller: a service stuck in blocking I/O must not hold the reply past the budget
    val run = CoroutineScope(Dispatchers.IO).async { runCatching { phone.call(pkg, method, args) } }
    val result = withTimeoutOrNull(budgetMs) { run.await() }
        ?: return replies(r.id, RpcStatus.UNAVAILABLE).also { run.cancel() }
    return result.fold({ replies(r.id, RpcStatus.OK, it) }, { replies(r.id, (it as? RpcError)?.status ?: RpcStatus.UNAVAILABLE) })
}

private fun storage(st: KeyValueStore, pkg: String, method: Int, args: ByteArray): List<ByteArray> = when (method) {
    Method.STORAGE_GET -> st.get(pkg, storageKey(args))?.let(::valueRecords) ?: emptyList()
    Method.STORAGE_PUT -> {
        val n = args.firstOrNull()?.toInt()?.and(0xFF) ?: throw RpcError(RpcStatus.BAD_REQUEST)
        if (args.size < 1 + n) throw RpcError(RpcStatus.BAD_REQUEST)
        st.put(pkg, storageKey(args.copyOfRange(1, 1 + n)), args.copyOfRange(1 + n, args.size))
        emptyList()
    }
    else -> { st.delete(pkg, storageKey(args)); emptyList() }
}

// background jobs

/** What a job fetches: an http.request, the weather, the calendar. */
object JobKind {
    const val HTTP = 0
    const val WEATHER = 1
    const val CALENDAR = 2
}

/** A watch app's background refresh (job.set): every [minutes], the records of one request, for the app's widget or
 *  page while it doesn't run. [on] is the user's switch; [last] ms; [target] a [PushKind]. */
data class RefreshJob(
    val id: Int, val minutes: Int, val kind: Int, val spec: ByteArray, val on: Boolean = true, val last: Long? = null,
    val target: Int = PushKind.BLOB,
) {
    /** What goes to the watch for these records: the widget record or the blob; null when they don't make one. */
    fun pushData(records: List<ByteArray>) = if (target == PushKind.WIDGET) jobWidget(records) else jobBlob(records)

    /** The cap its request needs, as the same request from the app would. */
    val cap: String get() = when (kind) {
        JobKind.HTTP -> "http:${parseHttpCall(spec)?.host.orEmpty()}"
        JobKind.WEATHER -> "weather"
        else -> "calendar"
    }

    /** The method whose records it delivers; [spec] is that method's args. */
    val method get() = when (kind) { JobKind.HTTP -> Method.HTTP_REQUEST; JobKind.WEATHER -> Method.WEATHER; else -> Method.CALENDAR }

    fun due(now: Long) = on && (last == null || now - last >= minutes * 60_000L)

    override fun equals(other: Any?) = other is RefreshJob && id == other.id && minutes == other.minutes && kind == other.kind &&
        spec.contentEquals(other.spec) && on == other.on && last == other.last && target == other.target
    override fun hashCode() = id
}

/** `job:u8 ‖ minutes:u16 ‖ kind:u8 ‖ target:u8 ‖ spec`: job 0-3, minutes clamped to 15..1440; spec http.request's
 *  joined payload (kind 0), weather's args (1), calendar.events' args (2); target 0 the widget (web requests only),
 *  1 the start blob. null for anything else. */
fun parseJob(d: ByteArray): RefreshJob? {
    if (d.size < 5 || d.u8(0) > 3 || d.u8(3) > JobKind.CALENDAR || d.u8(4) > PushKind.BLOB) return null
    if (d.u8(4) == PushKind.WIDGET && d.u8(3) != JobKind.HTTP) return null
    val spec = d.copyOfRange(5, d.size)
    val ok = when (d.u8(3)) {
        JobKind.HTTP -> parseHttpCall(spec) != null
        JobKind.WEATHER -> weatherArgs(spec) != null
        else -> spec.size == 3
    }
    if (!ok) return null
    return RefreshJob(d.u8(0), d.le16(1).coerceIn(15, 1440), d.u8(3), spec, target = d.u8(4))
}

/** A due job's records, as its request would answer them; DENIED once its cap is off. Never asks. */
suspend fun runJob(pkg: String, job: RefreshJob, phone: Phone): List<ByteArray> {
    if (phone.grants(pkg)[job.cap] != true || !phone.osAllows(job.cap)) throw RpcError(RpcStatus.DENIED)
    return phone.call(pkg, job.method, job.spec)
}
