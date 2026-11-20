package tools.fmc.companion.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** face-fit-vectors.json, as ios/WireKit FitTests.swift. */
class FitTest {
    private val v = Vectors.fit

    @Test fun decodedBytesPerFormat() {
        val a = v.getJSONArray("decoded")
        for (i in 0 until a.length()) {
            val d = a.getJSONObject(i)
            assertEquals(d.getInt("bytes"), decodedBytes(d.getInt("cf"), d.getInt("w"), d.getInt("h")), "cf ${d.getInt("cf")}")
        }
    }

    @Test fun etc2Blocks() {
        val a = v.getJSONArray("etc2")
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            val rgba = hex(e.getString("rgba")).map { it.toInt() and 0xFF }.toIntArray()
            assertEquals(e.getString("blocks"), encodeEtc2(rgba, e.getInt("w"), e.getInt("h")).hex(), e.getString("name"))
        }
    }

    @Test fun faces() {
        val a = v.getJSONArray("faces")
        for (n in 0 until a.length()) {
            val f = a.getJSONObject(n)
            val name = f.getString("name")
            val bin = hex(f.getString("hex"))
            assertEquals(f.getString("rebuild"), buildBin(parseBin(bin)).hex(), name)
            val face = parseBin(bin)
            val mem = f.getJSONArray("memory")
            face.screens.forEachIndexed { i, s ->
                val (bytes, fit) = screenMemory(s, face.resources)
                assertEquals(mem.getJSONObject(i).getInt("bytes"), bytes, name)
                assertEquals(mem.getJSONObject(i).getString("fit"), fit.site, name)
            }
            val fits = f.getJSONObject("fit")
            for (device in fits.keys()) {
                val want = fits.getJSONObject(device)
                val w3 = device == "watch_3_pro"
                val out = fitForWatch(bin, w3)
                assertEquals(want.getInt("compressed"), out.compressed, "$name $device")
                assertContentEquals(if (want.isNull("hex")) bin else hex(want.getString("hex")), out.bin, "$name $device")
                val verdict = checkFace(out.bin, w3)
                if (want.isNull("over")) {
                    assertTrue(want.isNull("refuse"))
                    assertFalse(verdict is FaceVerdict.Refuse, "$name $device: $verdict")
                } else {
                    val over = want.getJSONObject("over")
                    assertEquals(FaceVerdict.Refuse(FaceRefusal.ScreenOverBudget(over.getInt("tag") == 0x22, over.getInt("bytes"))), verdict)
                }
            }
        }
    }

    @Test fun watch3ProByName() {
        assertTrue(isWatch3Pro("CMF Watch 3 Pro-1A2B"))
        assertTrue(isWatch3Pro("cmf watch pro 3"))
        assertFalse(isWatch3Pro("CMF Watch Pro 2-1A2B"))
        assertFalse(isWatch3Pro(null))
    }
}
