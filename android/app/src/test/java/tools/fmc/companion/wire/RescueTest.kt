package tools.fmc.companion.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/** ios RescueTests, on the same rescue-vectors.json (tools/rescue-vectors.ts, from the site's own code). */
class RescueTest {
    private val v = Vectors.rescue
    private fun steps(name: String) = v.getJSONArray(name).let { a -> (0 until a.length()).map(a::getJSONObject) }
    private fun strings(a: JSONArray) = (0 until a.length()).map(a::getString)
    private fun h(key: String) = hex(v.getString(key))

    /** Each step's site writes are a prefix of ours, and we write nothing the site doesn't. */
    private fun replay(plan: RescuePlan, steps: List<JSONObject>): RescueStep {
        val all = steps.flatMap { strings(it.getJSONArray("writes")) }.toSet()
        var last = RescueStep()
        steps.forEachIndexed { i, s ->
            val ours = if (s.has("notify")) plan.notify(Uuid.CMD_NOTIFY, hex(s.getString("notify"))).also { last = it }.writes
            else plan.connected()
            val site = strings(s.getJSONArray("writes"))
            assertEquals(site, ours.take(site.size).map { it.data.hex() }, "step $i")
            assertTrue(ours.all { it.data.hex() in all && it.uuid == Uuid.CMD_WRITE }, "step $i")
        }
        return last
    }

    @Test fun savedKeyMatchesSite() {
        val last = replay(RescuePlan.of(RescueAction.FactoryReset, h("k1"), null)!!, steps("known"))
        assertTrue(last.done && !last.refused)
    }

    @Test fun bindsWithSecretWithoutTap() {
        val plan = RescuePlan.of(RescueAction.FactoryReset, null, h("secret"), h("random1"))!!
        assertTrue(replay(plan, steps("bind")).done)
        assertContentEquals(h("bound_k1"), plan.k1)
    }

    @Test fun resetRidesBehindConfirm() {
        val plan = RescuePlan.of(RescueAction.FactoryReset, h("k1"), null)!!
        plan.connected()
        plan.notify(Uuid.CMD_NOTIFY, hex(steps("known")[1].getString("notify")))
        val w = plan.notify(Uuid.CMD_NOTIFY, hex(steps("known")[2].getString("notify"))).writes.map { it.what }
        assertEquals(listOf("confirm", "factoryReset"), w)
    }

    @Test fun staleKeyFallsBackToSecret() {
        val failed = stockFrames(StockCmd.AUTH_FAILED, bytes(1, 2), null)[0]
        val plan = RescuePlan.of(RescueAction.FactoryReset, h("k1"), h("secret"), h("random1"))!!
        plan.connected()
        val s = plan.notify(Uuid.CMD_NOTIFY, failed)
        assertTrue(s.writes.map { it.what } == listOf("pair") && !s.refused && plan.k1 == null)
        val only = RescuePlan.of(RescueAction.FactoryReset, h("k1"), null)!!
        only.connected()
        assertTrue(only.notify(Uuid.CMD_NOTIFY, failed).refused)
    }

    @Test fun needsAKey() = assertNull(RescuePlan.of(RescueAction.FactoryReset, null, null))

    @Test fun keepDialsMatchesSiteCodec() {
        val k = v.getJSONObject("keep_dials")
        val ids = k.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getLong) }
        val plan = RescuePlan.of(RescueAction.KeepDials(ids, k.getInt("active")), h("k1"), null)!!
        plan.connected()
        plan.notify(Uuid.CMD_NOTIFY, hex(steps("known")[1].getString("notify")))
        val w = plan.notify(Uuid.CMD_NOTIFY, hex(steps("known")[2].getString("notify"))).writes.filter { it.what == "keepDials" }
        assertEquals(strings(k.getJSONArray("frames")), w.map { it.data.hex() })
    }

    @Test fun keepsStockDials() {
        val r = rescueKeep(listOf(0xFFFF_FFFFL, 12345, 273, 280, 99999), setOf(273L, 280L))
        assertEquals(listOf(0xFFFF_FFFFL, 273L, 280L), r?.ids)
        assertEquals(1, r?.active)
        assertNull(rescueKeep(listOf(12345L), setOf(273L)))
    }
}
