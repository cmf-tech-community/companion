import Foundation

// A watchface file as its node tree and resources, and back (fmc_frontend editor/core/format/bin.ts parseBin,
// buildBin), with the LZ4 block codec (lz4.ts) and the pixel formats fitForWatch re-encodes (pixels-codec.ts).
// Only what fitForWatch needs: it rebuilds the file byte for byte the way the site does (face-fit-vectors.json).
// Format: docs/cmf-protocol.md §9.6a.

public struct FaceNode: Sendable {
    public var tag: UInt8
    var subs: [FaceNode]?
    var text: String?
    var hex: [UInt8]?
    var x: Int?, y: Int?
    var meta: [UInt8]?, tail: [UInt8]?, prefix: [UInt8]?
    var refType: UInt8?
    /// Resource indices this node shows (a frame run lists each frame).
    var images: [Int]?
    var flag: UInt8?
    var pivotX: Int?, pivotY: Int?

    init(tag: UInt8) { self.tag = tag }
}

public struct FaceResource: Sendable {
    /// LVGL colour format: 1 JPEG, 4 RGB565, 5 RGB565+alpha, 13/14 alpha masks, 24 BGRA8888, 28 ETC2 RGBA8.
    var cf: Int, w: Int, h: Int
    /// LZ4 compressed (cf 1: the JPEG itself).
    var data: [UInt8]
}

public struct FaceBin: Sendable {
    /// The whole 16-byte name field, for an exact round trip.
    var nameRaw: [UInt8]
    public var screens: [FaceNode]
    var resources: [FaceResource]
}

enum FaceBinError: Error { case malformed(String) }

private func u16(_ d: [UInt8], _ o: Int) -> Int { Int(d[o]) | Int(d[o + 1]) << 8 }
private func u32(_ d: [UInt8], _ o: Int) -> Int { u16(d, o) | u16(d, o + 2) << 16 }
private func le16(_ v: Int) -> [UInt8] { [UInt8(truncatingIfNeeded: v), UInt8(truncatingIfNeeded: v >> 8)] }
private func le32(_ v: Int) -> [UInt8] { le16(v) + le16(v >> 16) }

private let crcTable: [UInt32] = (0..<256).map { n in
    (0..<8).reduce(UInt32(n)) { c, _ in c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
}

/// `rawCRC32`: IEEE reflected, init 0, no final inversion.
func rawCRC32(_ d: [UInt8]) -> Int {
    Int(d.reduce(UInt32(0)) { crcTable[Int(($0 ^ UInt32($1)) & 0xFF)] ^ ($0 >> 8) })
}

private let hdrSize = 36

// fixed-layout leaves the speculative TLV descent must not split (bind, fmt, frame, arc, arcClipped, slot)
private let leaf: Set<UInt8> = [0x02, 0x40, 0x48, 0x5A, 0x5B, 0x5F]

private struct TLVNode { var tag: UInt8; var raw: [UInt8]; var children: [TLVNode]? }

private func parseTLV(_ d: [UInt8], _ depth: Int) -> [TLVNode]? {
    var nodes: [TLVNode] = []
    var off = 0
    while off < d.count {
        guard off + 3 <= d.count else { return nil }
        let n = u16(d, off + 1)
        guard off + 3 + n <= d.count else { return nil }
        let v = Array(d[off + 3..<off + 3 + n])
        nodes.append(TLVNode(tag: d[off], raw: v, children: n >= 3 && depth < 12 ? parseTLV(v, depth + 1) : nil))
        off += 3 + n
    }
    return nodes
}

/// `parseRefTail`: [type][count u16][ref u32] then a u32 per further frame (0x01) or a u16 block size per frame
/// (0x61/0x71).
private func parseRefTail(_ v: [UInt8], _ off: Int, _ res: [FaceResource], _ at: [Int: Int]) -> (UInt8, [Int])? {
    guard off + 3 <= v.count else { return nil }
    let typ = v[off], count = u16(v, off + 1)
    var p = off + 3
    guard p + 4 <= v.count else { return nil }
    let ref = u32(v, p)
    p += 4
    guard let first = at[ref] else { return nil }
    if typ == 0x01 {
        guard count != 0, p + 4 * (count - 1) == v.count else { return nil }
        var images = [first]
        for k in 1..<count {
            guard let i = at[u32(v, p + 4 * (k - 1))] else { return nil }
            images.append(i)
        }
        return (typ, images)
    }
    guard typ == 0x61 || typ == 0x71, p + 2 * count == v.count, count != 0 else { return nil }
    var images: [Int] = []
    var cur = ref
    for k in 0..<count {
        guard let i = at[cur] else { return nil }
        images.append(i)
        if k < count - 1 {
            let blk = res[i].data.count + 8
            guard blk == u16(v, p + 2 * k) else { return nil }
            cur += blk
        }
    }
    return (typ, images)
}

private func node(_ n: TLVNode, _ res: [FaceResource], _ at: [Int: Int]) -> FaceNode {
    var j = FaceNode(tag: n.tag)
    let v = n.raw
    if n.tag == 0x86, v.count == 64, let e = v.firstIndex(of: 0), v[e...].allSatisfy({ $0 == 0 }) {
        j.text = String(decoding: v[..<e], as: UTF8.self)
        return j
    }
    if n.tag == 0x01, v.count >= 18 {
        j.x = Int(Int16(truncatingIfNeeded: u16(v, 0)))
        j.y = Int(Int16(truncatingIfNeeded: u16(v, 2)))
        j.meta = Array(v[4..<18])
        if v.count >= 25, let (t, images) = parseRefTail(v, 18, res, at) {
            (j.refType, j.images) = (t, images)
        } else if v.count > 18 {
            j.tail = Array(v[18...])
        }
        return j
    }
    if n.tag == 0x08, v.count >= 12, let (t, images) = parseRefTail(v, 5, res, at) {
        (j.prefix, j.refType, j.images) = (Array(v[..<5]), t, images)
        return j
    }
    if n.tag == 0x05, v.count == 5 {
        (j.flag, j.pivotX, j.pivotY) = (v[0], u16(v, 1), u16(v, 3))
        return j
    }
    if let kids = n.children, !leaf.contains(n.tag) {
        j.subs = kids.map { node($0, res, at) }
        return j
    }
    j.hex = v
    return j
}

/// `parseBin`.
func parseBin(_ data: Data) throws -> FaceBin {
    let d = [UInt8](data)
    guard d.count >= 2 * hdrSize + 3 else { throw FaceBinError.malformed("file too small") }
    guard d[4] == 1, d[5] == 0, d[6] == 0, d[7] == 0 || d[7] == 2 else { throw FaceBinError.malformed("no magic") }
    guard d.prefix(hdrSize).elementsEqual(d.suffix(hdrSize)) else { throw FaceBinError.malformed("footer differs") }
    let body = Array(d[hdrSize..<d.count - hdrSize])
    guard body[0] == 0x20 else { throw FaceBinError.malformed("root tag") }
    let rootLen = u16(body, 1)
    guard 3 + rootLen <= body.count else { throw FaceBinError.malformed("tree length") }
    guard let tree = parseTLV(Array(body[3..<3 + rootLen]), 0) else { throw FaceBinError.malformed("tree") }
    var res: [FaceResource] = []
    var at: [Int: Int] = [:]
    var off = 3 + rootLen
    while off < body.count {
        guard off + 8 <= body.count else { throw FaceBinError.malformed("truncated resource") }
        let h = u32(body, off), size = u32(body, off + 4)
        guard off + 8 + size <= body.count else { throw FaceBinError.malformed("resource exceeds body") }
        at[off + hdrSize] = res.count
        res.append(FaceResource(cf: h & 0x1F, w: h >> 10 & 0x7FF, h: h >> 21 & 0x7FF,
                                data: Array(body[off + 8..<off + 8 + size])))
        off += 8 + size
    }
    return FaceBin(nameRaw: Array(d[8..<0x18]), screens: tree.map { node($0, res, at) }, resources: res)
}

private func nodeBytes(_ j: FaceNode, _ res: [FaceResource], _ offsets: [Int]) throws -> [UInt8] {
    var val: [UInt8]
    if let text = j.text, !text.isEmpty {
        val = [UInt8](repeating: 0, count: 64)
        let t = Array(text.utf8.prefix(63))
        val.replaceSubrange(0..<t.count, with: t)
    } else if let images = j.images {
        if let x = j.x, let y = j.y {
            guard let meta = j.meta, meta.count == 14 else { throw FaceBinError.malformed("meta") }
            val = le16(x) + le16(y) + meta
        } else {
            guard let p = j.prefix, p.count == 5 else { throw FaceBinError.malformed("prefix") }
            val = p
        }
        val += try refTail(j.refType ?? 0, images, res, offsets)
    } else if let x = j.x, let y = j.y, j.tag == 0x01 {
        guard let meta = j.meta, meta.count == 14 else { throw FaceBinError.malformed("meta") }
        val = le16(x) + le16(y) + meta + (j.tail ?? [])
    } else if let px = j.pivotX, let py = j.pivotY {
        val = [j.flag ?? 0] + le16(px) + le16(py)
    } else if let subs = j.subs {
        val = try subs.flatMap { try nodeBytes($0, res, offsets) }
    } else {
        val = j.hex ?? []
    }
    return [j.tag] + le16(val.count) + val
}

private func refTail(_ type: UInt8, _ images: [Int], _ res: [FaceResource], _ offsets: [Int]) throws -> [UInt8] {
    for (k, i) in images.enumerated() {
        guard res.indices.contains(i) else { throw FaceBinError.malformed("resource out of range") }
        guard k == 0 || i == images[k - 1] + 1 else { throw FaceBinError.malformed("frames not consecutive") }
    }
    var b = [type] + le16(images.count) + le32(offsets[images[0]])
    if type == 0x01 { return b + images.dropFirst().flatMap { le32(offsets[$0]) } }
    guard type == 0x61 || type == 0x71 else { throw FaceBinError.malformed("refType") }
    for i in images { b += le16(res[i].data.count + 8) }
    return b
}

/// `buildBin`.
func buildBin(_ face: FaceBin) throws -> Data {
    let res = face.resources
    var offsets = [Int](repeating: 0, count: res.count)
    // the tree's length doesn't depend on the offsets in it: build once for it, again with them
    var off = hdrSize + 3 + (try face.screens.flatMap { try nodeBytes($0, res, offsets) }).count
    for i in res.indices {
        offsets[i] = off
        off += res[i].data.count + 8
    }
    let tb = try face.screens.flatMap { try nodeBytes($0, res, offsets) }
    guard tb.count <= 0xFFFF else { throw FaceBinError.malformed("TLV tree exceeds 64K") }
    let tree = [0x20] + le16(tb.count) + tb
    let rb = res.flatMap { r in le32(r.cf & 0x1F | (r.w & 0x7FF) << 10 | (r.h & 0x7FF) << 21) + le32(r.data.count) + r.data }
    var hdr = [UInt8](repeating: 0, count: hdrSize)
    hdr[4] = 1
    let name = face.nameRaw.prefix(16)
    hdr.replaceSubrange(8..<8 + name.count, with: name)
    func put(_ o: Int, _ v: Int) { hdr.replaceSubrange(o..<o + 4, with: le32(v)) }
    put(0x18, tree.count + rb.count + hdrSize)
    put(0x1C, rb.count)
    put(0x20, rawCRC32(rb))
    put(0, rawCRC32(Array(hdr[4...]) + tree))
    return Data(hdr + tree + rb + hdr)
}

// MARK: LZ4 block codec (lz4.ts)

func lz4Decompress(_ src: [UInt8], _ outSize: Int) throws -> [UInt8] {
    var dst: [UInt8] = []
    dst.reserveCapacity(outSize)
    var i = 0
    func more(_ n: inout Int) throws {
        var b: UInt8
        repeat {
            guard i < src.count else { throw FaceBinError.malformed("lz4: truncated") }
            b = src[i]; i += 1; n += Int(b)
        } while b == 255
    }
    while i < src.count {
        let token = Int(src[i]); i += 1
        var lit = token >> 4
        if lit == 15 { try more(&lit) }
        guard i + lit <= src.count, dst.count + lit <= outSize else { throw FaceBinError.malformed("lz4: literals") }
        dst += src[i..<i + lit]
        i += lit
        if i >= src.count { break }
        guard i + 2 <= src.count else { throw FaceBinError.malformed("lz4: truncated") }
        let off = Int(src[i]) | Int(src[i + 1]) << 8
        i += 2
        var mLen = (token & 15) + 4
        if token & 15 == 15 { try more(&mLen) }
        guard off != 0, off <= dst.count, dst.count + mLen <= outSize else { throw FaceBinError.malformed("lz4: match") }
        let pos = dst.count - off
        for k in 0..<mLen { dst.append(dst[pos + k]) }
    }
    return dst
}

/// The site's greedy encoder, byte for byte: the watch takes any valid stream, the vectors only this one.
func lz4Compress(_ src: [UInt8]) -> [UInt8] {
    var dst: [UInt8] = []
    func length(_ n: Int) {
        var l = n - 15
        while l >= 255 { dst.append(255); l -= 255 }
        dst.append(UInt8(l))
    }
    func seq(_ litStart: Int, _ litEnd: Int, _ off: Int, _ mLen: Int) {
        let lit = litEnd - litStart, ml = off > 0 ? mLen - 4 : 0
        var tok = lit >= 15 ? 0xF0 : lit << 4
        if off > 0 { tok |= min(ml, 15) }
        dst.append(UInt8(tok))
        if lit >= 15 { length(lit) }
        dst += src[litStart..<litEnd]
        if off > 0 {
            dst += le16(off)
            if ml >= 15 { length(ml) }
        }
    }
    let n = src.count
    guard n >= 13 else { seq(0, n, 0, 0); return dst }
    func rd(_ p: Int) -> UInt32 {
        UInt32(src[p]) | UInt32(src[p + 1]) << 8 | UInt32(src[p + 2]) << 16 | UInt32(src[p + 3]) << 24
    }
    var table = [Int](repeating: -1, count: 1 << 14)
    var litStart = 0, i = 0
    let limit = n - 5
    while i < n - 12 {
        let h = Int((rd(i) &* 2_654_435_761) >> 18 & 0x3FFF)
        let cand = table[h]
        table[h] = i
        if cand >= 0, i - cand <= 65535, rd(cand) == rd(i) {
            var mLen = 4
            while i + mLen < limit, src[cand + mLen] == src[i + mLen] { mLen += 1 }
            seq(litStart, i, i - cand, mLen)
            i += mLen
            litStart = i
            continue
        }
        i += 1
    }
    seq(litStart, n, 0, 0)
    return dst
}

// MARK: pixels (pixels-codec.ts), the formats fitForWatch reads and writes

/// `decodePixels` for cf 4, 5 and 24: RGBA.
func decodePixels(_ r: FaceResource) throws -> [UInt8] {
    let bpp = [4: 2, 5: 3, 24: 4]
    guard let b = bpp[r.cf] else { throw FaceBinError.malformed("cf \(r.cf) not decoded here") }
    let n = r.w * r.h
    let raw = try lz4Decompress(r.data, n * b)
    guard raw.count == n * b else { throw FaceBinError.malformed("cf \(r.cf) \(r.w)x\(r.h): short") }
    var px = [UInt8](repeating: 255, count: n * 4)
    func rgb565(_ c: Int, _ o: Int) {
        px[o] = UInt8((c >> 11) * 255 / 31)
        px[o + 1] = UInt8(((c >> 5) & 63) * 255 / 63)
        px[o + 2] = UInt8((c & 31) * 255 / 31)
    }
    for i in 0..<n {
        let o = i * 4
        switch r.cf {
        case 4: rgb565(u16(raw, i * 2), o)
        case 5: rgb565(u16(raw, i * 3), o); px[o + 3] = raw[i * 3 + 2]
        default: (px[o], px[o + 1], px[o + 2], px[o + 3]) = (raw[i * 4 + 2], raw[i * 4 + 1], raw[i * 4], raw[i * 4 + 3])
        }
    }
    return px
}

/// `encodePixels` for cf 4 and 28.
func encodePixels(_ px: [UInt8], _ w: Int, _ h: Int, _ cf: Int) throws -> FaceResource {
    guard w <= 2047, h <= 2047 else { throw FaceBinError.malformed("image \(w)x\(h) does not fit 11-bit fields") }
    if cf == 28 { return FaceResource(cf: cf, w: w, h: h, data: lz4Compress(encodeEtc2(px, w, h))) }
    guard cf == 4 else { throw FaceBinError.malformed("cf \(cf) not encoded here") }
    var raw = [UInt8](repeating: 0, count: w * h * 2)
    for i in 0..<w * h {
        let c = (Int(px[i * 4]) >> 3) << 11 | (Int(px[i * 4 + 1]) >> 2) << 5 | Int(px[i * 4 + 2]) >> 3
        (raw[i * 2], raw[i * 2 + 1]) = (UInt8(c & 0xFF), UInt8(c >> 8))
    }
    return FaceResource(cf: cf, w: w, h: h, data: lz4Compress(raw))
}
