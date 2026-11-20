package tools.fmc.companion.wire

import java.io.ByteArrayOutputStream

// A watchface file as its node tree and resources, and back (fmc_frontend editor/core/format/bin.ts parseBin,
// buildBin), with the LZ4 block codec (lz4.ts) and the pixel formats fitForWatch re-encodes (pixels-codec.ts).
// Only what fitForWatch needs: it rebuilds the file byte for byte the way the site does (face-fit-vectors.json).
// Port of ios/WireKit FaceBin.swift. Format: docs/cmf-protocol.md §9.6a.

class FaceNode(val tag: Int) {
    var subs: List<FaceNode>? = null
    var text: String? = null
    var hex: ByteArray? = null
    var x: Int? = null
    var y: Int? = null
    var meta: ByteArray? = null
    var tail: ByteArray? = null
    var prefix: ByteArray? = null
    var refType: Int? = null
    /** Resource indices this node shows (a frame run lists each frame). */
    var images: List<Int>? = null
    var flag: Int? = null
    var pivotX: Int? = null
    var pivotY: Int? = null
}

/** [cf]: LVGL colour format, 1 JPEG, 4 RGB565, 5 RGB565+alpha, 13/14 alpha masks, 24 BGRA8888, 28 ETC2 RGBA8. */
class FaceResource(val cf: Int, val w: Int, val h: Int, val data: ByteArray)

class FaceBin(val nameRaw: ByteArray, val screens: List<FaceNode>, val resources: MutableList<FaceResource>)

class FaceBinError(message: String) : Exception(message)

private fun ByteArray.u32i(o: Int) = le32(o).toInt()
private fun Buf.bytes(b: ByteArray) = add(b)

private val crcTable = IntArray(256) { n -> (0 until 8).fold(n) { c, _ -> if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else c ushr 1 } }

/** `rawCRC32`: IEEE reflected, init 0, no final inversion. */
fun rawCRC32(d: ByteArray): Long =
    d.fold(0) { c, b -> crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8) }.toLong() and 0xFFFFFFFFL

private const val HDR = 36

// fixed-layout leaves the speculative TLV descent must not split (bind, fmt, frame, arc, arcClipped, slot)
private val LEAF = setOf(0x02, 0x40, 0x48, 0x5A, 0x5B, 0x5F)

private class Tlv(val tag: Int, val raw: ByteArray, val children: List<Tlv>?)

private fun parseTlv(d: ByteArray, depth: Int): List<Tlv>? {
    val nodes = mutableListOf<Tlv>()
    var off = 0
    while (off < d.size) {
        if (off + 3 > d.size) return null
        val n = d.le16(off + 1)
        if (off + 3 + n > d.size) return null
        val v = d.copyOfRange(off + 3, off + 3 + n)
        nodes += Tlv(d.u8(off), v, if (n >= 3 && depth < 12) parseTlv(v, depth + 1) else null)
        off += 3 + n
    }
    return nodes
}

/** `parseRefTail`: [type][count u16][ref u32] then a u32 per further frame (0x01) or a u16 block size per frame. */
private fun parseRefTail(v: ByteArray, off: Int, res: List<FaceResource>, at: Map<Int, Int>): Pair<Int, List<Int>>? {
    if (off + 3 > v.size) return null
    val typ = v.u8(off)
    val count = v.le16(off + 1)
    var p = off + 3
    if (p + 4 > v.size) return null
    val ref = v.u32i(p)
    p += 4
    val first = at[ref] ?: return null
    if (typ == 0x01) {
        if (count == 0 || p + 4 * (count - 1) != v.size) return null
        val images = mutableListOf(first)
        for (k in 1 until count) images += at[v.u32i(p + 4 * (k - 1))] ?: return null
        return typ to images
    }
    if (typ != 0x61 && typ != 0x71 || p + 2 * count != v.size || count == 0) return null
    val images = mutableListOf<Int>()
    var cur = ref
    for (k in 0 until count) {
        val i = at[cur] ?: return null
        images += i
        if (k < count - 1) {
            val blk = res[i].data.size + 8
            if (blk != v.le16(p + 2 * k)) return null
            cur += blk
        }
    }
    return typ to images
}

private fun node(n: Tlv, res: List<FaceResource>, at: Map<Int, Int>): FaceNode {
    val j = FaceNode(n.tag)
    val v = n.raw
    if (n.tag == 0x86 && v.size == 64) {
        val e = v.indexOf(0)
        if (e >= 0 && (e until 64).all { v[it].toInt() == 0 }) {
            j.text = String(v, 0, e, Charsets.UTF_8)
            return j
        }
    }
    if (n.tag == 0x01 && v.size >= 18) {
        j.x = v.le16(0).toShort().toInt()
        j.y = v.le16(2).toShort().toInt()
        j.meta = v.copyOfRange(4, 18)
        val rt = if (v.size >= 25) parseRefTail(v, 18, res, at) else null
        if (rt != null) {
            j.refType = rt.first
            j.images = rt.second
        } else if (v.size > 18) {
            j.tail = v.copyOfRange(18, v.size)
        }
        return j
    }
    if (n.tag == 0x08 && v.size >= 12) {
        parseRefTail(v, 5, res, at)?.let { (t, images) ->
            j.prefix = v.copyOfRange(0, 5)
            j.refType = t
            j.images = images
            return j
        }
    }
    if (n.tag == 0x05 && v.size == 5) {
        j.flag = v.u8(0)
        j.pivotX = v.le16(1)
        j.pivotY = v.le16(3)
        return j
    }
    if (n.children != null && n.tag !in LEAF) {
        j.subs = n.children.map { node(it, res, at) }
        return j
    }
    j.hex = v
    return j
}

/** `parseBin`. */
fun parseBin(d: ByteArray): FaceBin {
    if (d.size < 2 * HDR + 3) throw FaceBinError("file too small")
    if (!(d[4].toInt() == 1 && d[5].toInt() == 0 && d[6].toInt() == 0 && (d[7].toInt() == 0 || d[7].toInt() == 2)))
        throw FaceBinError("no magic")
    if (!d.copyOfRange(0, HDR).contentEquals(d.copyOfRange(d.size - HDR, d.size))) throw FaceBinError("footer differs")
    val body = d.copyOfRange(HDR, d.size - HDR)
    if (body.u8(0) != 0x20) throw FaceBinError("root tag")
    val rootLen = body.le16(1)
    if (3 + rootLen > body.size) throw FaceBinError("tree length")
    val tree = parseTlv(body.copyOfRange(3, 3 + rootLen), 0) ?: throw FaceBinError("tree")
    val res = mutableListOf<FaceResource>()
    val at = mutableMapOf<Int, Int>()
    var off = 3 + rootLen
    while (off < body.size) {
        if (off + 8 > body.size) throw FaceBinError("truncated resource")
        val h = body.u32i(off)
        val size = body.u32i(off + 4)
        if (size < 0 || off + 8 + size > body.size) throw FaceBinError("resource exceeds body")
        at[off + HDR] = res.size
        res += FaceResource(h and 0x1F, (h ushr 10) and 0x7FF, (h ushr 21) and 0x7FF, body.copyOfRange(off + 8, off + 8 + size))
        off += 8 + size
    }
    return FaceBin(d.copyOfRange(8, 0x18), tree.map { node(it, res, at) }, res)
}

private fun nodeBytes(j: FaceNode, res: List<FaceResource>, offsets: IntArray): ByteArray {
    val text = j.text
    val images = j.images
    val x = j.x
    val y = j.y
    val px = j.pivotX
    val py = j.pivotY
    val subs = j.subs
    val v = when {
        !text.isNullOrEmpty() -> ByteArray(64).also { text.toByteArray().let { t -> t.copyInto(it, 0, 0, minOf(t.size, 63)) } }
        images != null -> buf {
            if (x != null && y != null) le16(x).le16(y).bytes(meta(j))
            else add(j.prefix?.takeIf { it.size == 5 } ?: throw FaceBinError("prefix"))
            add(refTail(j.refType ?: 0, images, res, offsets))
        }
        x != null && y != null && j.tag == 0x01 -> buf { le16(x).le16(y).bytes(meta(j)).add(j.tail ?: ByteArray(0)) }
        px != null && py != null -> buf { u8(j.flag ?: 0).le16(px).le16(py) }
        subs != null -> buf { subs.forEach { add(nodeBytes(it, res, offsets)) } }
        else -> j.hex ?: ByteArray(0)
    }
    return buf { u8(j.tag).le16(v.size).add(v) }
}

private fun meta(j: FaceNode) = j.meta?.takeIf { it.size == 14 } ?: throw FaceBinError("meta")

private fun refTail(type: Int, images: List<Int>, res: List<FaceResource>, offsets: IntArray): ByteArray {
    images.forEachIndexed { k, i ->
        if (i !in res.indices) throw FaceBinError("resource out of range")
        if (k > 0 && i != images[k - 1] + 1) throw FaceBinError("frames not consecutive")
    }
    return buf {
        u8(type).le16(images.size).le32(offsets[images[0]].toLong())
        if (type == 0x01) {
            images.drop(1).forEach { le32(offsets[it].toLong()) }
        } else {
            if (type != 0x61 && type != 0x71) throw FaceBinError("refType")
            images.forEach { le16(res[it].data.size + 8) }
        }
    }
}

/** `buildBin`. */
fun buildBin(face: FaceBin): ByteArray {
    val res = face.resources
    val offsets = IntArray(res.size)
    fun tree() = buf { face.screens.forEach { add(nodeBytes(it, res, offsets)) } }
    // the tree's length doesn't depend on the offsets in it: build once for it, again with them
    var off = HDR + 3 + tree().size
    for (i in res.indices) {
        offsets[i] = off
        off += res[i].data.size + 8
    }
    val tb = tree()
    if (tb.size > 0xFFFF) throw FaceBinError("TLV tree exceeds 64K")
    val treeSec = buf { u8(0x20).le16(tb.size).add(tb) }
    val out = ByteArrayOutputStream()
    for (r in res) {
        out.write(buf { le32(((r.cf and 0x1F) or ((r.w and 0x7FF) shl 10) or ((r.h and 0x7FF) shl 21)).toLong()).le32(r.data.size.toLong()) })
        out.write(r.data)
    }
    val rb = out.toByteArray()
    val hdr = ByteArray(HDR)
    hdr[4] = 1
    face.nameRaw.copyInto(hdr, 8, 0, minOf(16, face.nameRaw.size))
    fun put(o: Int, v: Long) = buf { le32(v) }.copyInto(hdr, o)
    put(0x18, (treeSec.size + rb.size + HDR).toLong())
    put(0x1C, rb.size.toLong())
    put(0x20, rawCRC32(rb))
    put(0, rawCRC32(hdr.copyOfRange(4, HDR) + treeSec))
    return hdr + treeSec + rb + hdr
}

// ---- LZ4 block codec (lz4.ts) ----

fun lz4Decompress(src: ByteArray, outSize: Int): ByteArray {
    val dst = ByteArray(outSize)
    var d = 0
    var i = 0
    fun more(n: Int): Int {
        var m = n
        do {
            if (i >= src.size) throw FaceBinError("lz4: truncated")
            val b = src.u8(i++)
            m += b
        } while (b == 255)
        return m
    }
    while (i < src.size) {
        val token = src.u8(i++)
        var lit = token shr 4
        if (lit == 15) lit = more(lit)
        if (i + lit > src.size || d + lit > outSize) throw FaceBinError("lz4: literals")
        src.copyInto(dst, d, i, i + lit)
        d += lit
        i += lit
        if (i >= src.size) break
        if (i + 2 > src.size) throw FaceBinError("lz4: truncated")
        val off = src.le16(i)
        i += 2
        var mLen = (token and 15) + 4
        if (token and 15 == 15) mLen = more(mLen)
        if (off == 0 || off > d || d + mLen > outSize) throw FaceBinError("lz4: match")
        val pos = d - off
        for (k in 0 until mLen) dst[d++] = dst[pos + k]
    }
    return if (d == outSize) dst else dst.copyOf(d)
}

/** The site's greedy encoder, byte for byte: the watch takes any valid stream, the vectors only this one. */
fun lz4Compress(src: ByteArray): ByteArray {
    val dst = ByteArrayOutputStream()
    fun length(n: Int) {
        var l = n - 15
        while (l >= 255) { dst.write(255); l -= 255 }
        dst.write(l)
    }
    fun seq(litStart: Int, litEnd: Int, off: Int, mLen: Int) {
        val lit = litEnd - litStart
        val ml = if (off > 0) mLen - 4 else 0
        var tok = if (lit >= 15) 0xF0 else lit shl 4
        if (off > 0) tok = tok or minOf(ml, 15)
        dst.write(tok)
        if (lit >= 15) length(lit)
        dst.write(src, litStart, lit)
        if (off > 0) {
            dst.write(off and 0xFF); dst.write(off shr 8)
            if (ml >= 15) length(ml)
        }
    }
    val n = src.size
    if (n < 13) { seq(0, n, 0, 0); return dst.toByteArray() }
    fun rd(p: Int) = src.u8(p) or (src.u8(p + 1) shl 8) or (src.u8(p + 2) shl 16) or (src.u8(p + 3) shl 24)
    val table = IntArray(1 shl 14) { -1 }
    var litStart = 0
    var i = 0
    val limit = n - 5
    while (i < n - 12) {
        val h = ((rd(i) * -1640531535) ushr 18) and 0x3FFF // Math.imul(x, 2654435761)
        val cand = table[h]
        table[h] = i
        if (cand >= 0 && i - cand <= 65535 && rd(cand) == rd(i)) {
            var mLen = 4
            while (i + mLen < limit && src[cand + mLen] == src[i + mLen]) mLen++
            seq(litStart, i, i - cand, mLen)
            i += mLen
            litStart = i
            continue
        }
        i++
    }
    seq(litStart, n, 0, 0)
    return dst.toByteArray()
}

// ---- pixels (pixels-codec.ts), the formats fitForWatch reads and writes ----

/** `decodePixels` for cf 4, 5 and 24: RGBA, one Int per channel. */
fun decodePixels(r: FaceResource): IntArray {
    val b = when (r.cf) { 4 -> 2; 5 -> 3; 24 -> 4; else -> throw FaceBinError("cf ${r.cf} not decoded here") }
    val n = r.w * r.h
    val raw = lz4Decompress(r.data, n * b)
    if (raw.size != n * b) throw FaceBinError("cf ${r.cf} ${r.w}x${r.h}: short")
    val px = IntArray(n * 4) { 255 }
    fun rgb565(c: Int, o: Int) {
        px[o] = (c shr 11) * 255 / 31
        px[o + 1] = ((c shr 5) and 63) * 255 / 63
        px[o + 2] = (c and 31) * 255 / 31
    }
    for (i in 0 until n) {
        val o = i * 4
        when (r.cf) {
            4 -> rgb565(raw.le16(i * 2), o)
            5 -> { rgb565(raw.le16(i * 3), o); px[o + 3] = raw.u8(i * 3 + 2) }
            else -> { px[o] = raw.u8(i * 4 + 2); px[o + 1] = raw.u8(i * 4 + 1); px[o + 2] = raw.u8(i * 4); px[o + 3] = raw.u8(i * 4 + 3) }
        }
    }
    return px
}

/** `encodePixels` for cf 4 and 28. */
fun encodePixels(px: IntArray, w: Int, h: Int, cf: Int): FaceResource {
    if (w > 2047 || h > 2047) throw FaceBinError("image ${w}x$h does not fit 11-bit fields")
    if (cf == 28) return FaceResource(cf, w, h, lz4Compress(encodeEtc2(px, w, h)))
    if (cf != 4) throw FaceBinError("cf $cf not encoded here")
    val raw = ByteArray(w * h * 2)
    for (i in 0 until w * h) {
        val c = ((px[i * 4] shr 3) shl 11) or ((px[i * 4 + 1] shr 2) shl 5) or (px[i * 4 + 2] shr 3)
        raw[i * 2] = c.toByte()
        raw[i * 2 + 1] = (c shr 8).toByte()
    }
    return FaceResource(cf, w, h, lz4Compress(raw))
}
