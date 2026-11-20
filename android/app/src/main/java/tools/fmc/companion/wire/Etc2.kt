package tools.fmc.companion.wire

import kotlin.math.floor

// The ETC2 RGBA8 encoder of fmc_frontend editor/core/format/etc2.ts (encodeEtc2): what cf 28 resources on the Watch 3
// Pro are, 4×4-pixel blocks of 16 bytes (8 EAC alpha, 8 colour), row-major over the image padded to a multiple of 4.
// Spec: OpenGL ES 3.0, appendix C. The site's choices, block for block (face-fit-vectors.json): ETC1-compatible
// individual/differential blocks only, a per-subblock average as the base colour, no refinement. Port of
// ios/WireKit Etc2.swift.

private val MOD = arrayOf(intArrayOf(2, 8), intArrayOf(5, 17), intArrayOf(9, 29), intArrayOf(13, 42),
    intArrayOf(18, 60), intArrayOf(24, 80), intArrayOf(33, 106), intArrayOf(47, 183))
private val EAC = arrayOf(
    intArrayOf(-3, -6, -9, -15, 2, 5, 8, 14), intArrayOf(-3, -7, -10, -13, 2, 6, 9, 12),
    intArrayOf(-2, -5, -8, -13, 1, 4, 7, 12), intArrayOf(-2, -4, -6, -13, 1, 3, 5, 12),
    intArrayOf(-3, -6, -8, -12, 2, 5, 7, 11), intArrayOf(-3, -7, -9, -11, 2, 6, 8, 10),
    intArrayOf(-4, -7, -8, -11, 3, 6, 7, 10), intArrayOf(-3, -5, -8, -11, 2, 4, 7, 10),
    intArrayOf(-2, -6, -8, -10, 1, 5, 7, 9), intArrayOf(-2, -5, -8, -10, 1, 4, 7, 9),
    intArrayOf(-2, -4, -8, -10, 1, 3, 7, 9), intArrayOf(-2, -5, -7, -10, 1, 4, 6, 9),
    intArrayOf(-3, -4, -7, -10, 2, 3, 6, 9), intArrayOf(-1, -2, -3, -10, 0, 1, 2, 9),
    intArrayOf(-4, -6, -8, -9, 3, 5, 7, 8), intArrayOf(-3, -5, -7, -9, 2, 4, 6, 8),
)

private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
private fun sq(v: Int) = v * v

/** JavaScript's Math.round: the nearest integer, ties up. */
fun jsRound(x: Double): Int {
    val f = floor(x)
    return (if (x - f >= 0.5) f + 1 else f).toInt()
}

fun etc2Size(w: Int, h: Int) = ((w + 3) shr 2) * ((h + 3) shr 2) * 16

/** RGBA pixels → ETC2 RGBA8 blocks, edge blocks padded by repeating the last row/column. */
fun encodeEtc2(rgba: IntArray, w: Int, h: Int): ByteArray {
    val out = ByteArray(etc2Size(w, h))
    val bw = (w + 3) shr 2
    val blk = IntArray(64)
    for (n in 0 until out.size / 16) {
        val bx = n % bw * 4
        val by = n / bw * 4
        for (i in 0 until 16) {
            val p = (minOf(by + (i and 3), h - 1) * w + minOf(bx + (i shr 2), w - 1)) * 4
            rgba.copyInto(blk, i * 4, p, p + 4)
        }
        alphaBlock(blk, out, n * 16)
        rgbBlock(blk, out, n * 16 + 8)
    }
    return out
}

/** One 8-byte EAC alpha block from 16 pixels in block order (i = x*4+y). */
private fun alphaBlock(px: IntArray, out: ByteArray, o: Int) {
    var lo = 255
    var hi = 0
    for (i in 0 until 16) { lo = minOf(lo, px[i * 4 + 3]); hi = maxOf(hi, px[i * 4 + 3]) }
    // table 13 holds a 0 modifier at index 4: a flat block is exact
    var bestErr = if (lo == hi) 0 else Int.MAX_VALUE
    var bestBase = lo
    var bestMult = 1
    var bestT = 13
    var bestIdx = IntArray(16) { 4 }
    var t = 0
    while (t < 16 && bestErr > 0) {
        val tb = EAC[t]
        val tMin = tb.min()
        val tMax = tb.max()
        for (mult in 1 until 16) {
            val base = clamp(jsRound((lo + hi) / 2.0 - (tMin + tMax) / 2.0 * mult))
            val idx = IntArray(16)
            var err = 0
            var i = 0
            while (i < 16 && err < bestErr) {
                val a = px[i * 4 + 3]
                var e = Int.MAX_VALUE
                for (k in 0 until 8) {
                    val v = sq(clamp(base + tb[k] * mult) - a)
                    if (v < e) { e = v; idx[i] = k }
                }
                err += e
                i++
            }
            if (err < bestErr) { bestErr = err; bestBase = base; bestMult = mult; bestT = t; bestIdx = idx }
        }
        t++
    }
    var h = 0
    var l = 0
    for (i in 0 until 8) h = h or (bestIdx[i] shl (21 - 3 * i))
    for (i in 8 until 16) l = l or (bestIdx[i] shl (45 - 3 * i))
    out[o] = bestBase.toByte()
    out[o + 1] = ((bestMult shl 4) or bestT).toByte()
    intArrayOf(h shr 16, h shr 8, h, l shr 16, l shr 8, l).forEachIndexed { k, v -> out[o + 2 + k] = v.toByte() }
}

/** One 8-byte colour block from 16 pixels in block order. */
private fun rgbBlock(px: IntArray, out: ByteArray, o: Int) {
    var bestErr = Int.MAX_VALUE
    val first = BooleanArray(16)
    for (flip in 0 until 2) {
        val sum = Array(2) { IntArray(3) }
        for (i in 0 until 16) {
            first[i] = if (flip == 1) (i and 3) < 2 else (i shr 2) < 2
            for (k in 0 until 3) sum[if (first[i]) 0 else 1][k] += px[i * 4 + k]
        }
        val avg = sum.map { s -> s.map { it / 8.0 } }
        val q5 = avg.map { c -> c.map { jsRound(it * 31 / 255) } }
        val d = (0 until 3).map { q5[1][it] - q5[0][it] }
        val diff = d.all { it in -4..3 }
        val q = if (diff) q5 else avg.map { c -> c.map { jsRound(it * 15 / 255) } }
        val bases = q.map { c -> c.map { if (diff) (it shl 3) or (it shr 2) else it * 17 } }
        val tables = IntArray(2)
        val idx = IntArray(16)
        var total = 0
        for (half in 0 until 2) {
            var tErr = Int.MAX_VALUE
            val ks = IntArray(16)
            for (t in 0 until 8) {
                var e = 0
                for (i in 0 until 16) {
                    if (first[i] != (half == 0)) continue
                    val r = bestIdx(px[i * 4], px[i * 4 + 1], px[i * 4 + 2], bases[half], MOD[t])
                    ks[i] = r shr 24
                    e += r and 0xFFFFFF
                }
                if (e < tErr) {
                    tErr = e
                    tables[half] = t
                    for (i in 0 until 16) if (first[i] == (half == 0)) idx[i] = ks[i]
                }
            }
            total += tErr
        }
        if (total >= bestErr) continue
        bestErr = total
        for (k in 0 until 3) {
            out[o + k] = (if (diff) (q[0][k] shl 3) or (d[k] and 7) else (q[0][k] shl 4) or q[1][k]).toByte()
        }
        out[o + 3] = ((tables[0] shl 5) or (tables[1] shl 2) or (if (diff) 2 else 0) or flip).toByte()
        var lo = 0
        for (i in 0 until 16) lo = lo or ((idx[i] shr 1) shl (16 + i)) or ((idx[i] and 1) shl i)
        for (k in 0 until 4) out[o + 4 + k] = (lo ushr (24 - 8 * k)).toByte()
    }
}

/** Best modifier index for one pixel against base ± table entry: index shl 24 or its error (< 2^18). */
private fun bestIdx(r: Int, g: Int, b: Int, base: List<Int>, t: IntArray): Int {
    var best = 0
    var err = Int.MAX_VALUE
    for (k in 0 until 4) {
        val m = (if (k and 1 != 0) t[1] else t[0]) * (if (k and 2 != 0) -1 else 1)
        val e = sq(clamp(base[0] + m) - r) + sq(clamp(base[1] + m) - g) + sq(clamp(base[2] + m) - b)
        if (e < err) { best = k; err = e }
    }
    return (best shl 24) or err
}
