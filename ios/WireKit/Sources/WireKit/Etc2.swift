import Foundation

// The ETC2 RGBA8 encoder of fmc_frontend editor/core/format/etc2.ts (encodeEtc2): what cf 28 resources on the Watch 3
// Pro are, 4×4-pixel blocks of 16 bytes (8 EAC alpha, 8 colour), row-major over the image padded to a multiple of 4.
// Spec: OpenGL ES 3.0, appendix C. The site's choices, block for block (face-fit-vectors.json): ETC1-compatible
// individual/differential blocks only, a per-subblock average as the base colour, no refinement.

private let mod = [[2, 8], [5, 17], [9, 29], [13, 42], [18, 60], [24, 80], [33, 106], [47, 183]]
private let eac = [
    [-3, -6, -9, -15, 2, 5, 8, 14], [-3, -7, -10, -13, 2, 6, 9, 12], [-2, -5, -8, -13, 1, 4, 7, 12],
    [-2, -4, -6, -13, 1, 3, 5, 12], [-3, -6, -8, -12, 2, 5, 7, 11], [-3, -7, -9, -11, 2, 6, 8, 10],
    [-4, -7, -8, -11, 3, 6, 7, 10], [-3, -5, -8, -11, 2, 4, 7, 10], [-2, -6, -8, -10, 1, 5, 7, 9],
    [-2, -5, -8, -10, 1, 4, 7, 9], [-2, -4, -8, -10, 1, 3, 7, 9], [-2, -5, -7, -10, 1, 4, 6, 9],
    [-3, -4, -7, -10, 2, 3, 6, 9], [-1, -2, -3, -10, 0, 1, 2, 9], [-4, -6, -8, -9, 3, 5, 7, 8],
    [-3, -5, -7, -9, 2, 4, 6, 8],
]

private func clamp(_ v: Int) -> Int { v < 0 ? 0 : v > 255 ? 255 : v }
private func sq(_ v: Int) -> Int { v * v }
/// JavaScript's Math.round: the nearest integer, ties up.
func jsRound(_ x: Double) -> Int {
    let f = x.rounded(.down)
    return Int(x - f >= 0.5 ? f + 1 : f)
}

/// Padded buffer size of a w×h ETC2 RGBA8 texture.
func etc2Size(_ w: Int, _ h: Int) -> Int { ((w + 3) >> 2) * ((h + 3) >> 2) * 16 }

/// RGBA pixels → ETC2 RGBA8 blocks, edge blocks padded by repeating the last row/column.
func encodeEtc2(_ rgba: [UInt8], _ w: Int, _ h: Int) -> [UInt8] {
    var out = [UInt8](repeating: 0, count: etc2Size(w, h))
    let bw = (w + 3) >> 2
    var blk = [UInt8](repeating: 0, count: 64)
    for n in 0..<out.count / 16 {
        let bx = n % bw * 4, by = n / bw * 4
        for i in 0..<16 {
            let p = (min(by + (i & 3), h - 1) * w + min(bx + (i >> 2), w - 1)) * 4
            blk.replaceSubrange(i * 4..<i * 4 + 4, with: rgba[p..<p + 4])
        }
        alphaBlock(blk, &out, n * 16)
        rgbBlock(blk, &out, n * 16 + 8)
    }
    return out
}

/// One 8-byte EAC alpha block from 16 pixels in block order (i = x*4+y).
private func alphaBlock(_ px: [UInt8], _ out: inout [UInt8], _ o: Int) {
    var lo = 255, hi = 0
    for i in 0..<16 { (lo, hi) = (min(lo, Int(px[i * 4 + 3])), max(hi, Int(px[i * 4 + 3]))) }
    // table 13 holds a 0 modifier at index 4: a flat block is exact
    var best = (err: lo == hi ? 0 : Int.max, base: lo, mult: 1, t: 13, idx: [Int](repeating: 4, count: 16))
    var t = 0
    while t < 16, best.err > 0 {
        let tb = eac[t], tMin = tb.min()!, tMax = tb.max()!
        for mult in 1..<16 {
            let base = clamp(jsRound(Double(lo + hi) / 2 - Double(tMin + tMax) / 2 * Double(mult)))
            var idx = [Int](repeating: 0, count: 16), err = 0, i = 0
            while i < 16, err < best.err {
                let a = Int(px[i * 4 + 3])
                var e = Int.max
                for k in 0..<8 {
                    let v = sq(clamp(base + tb[k] * mult) - a)
                    if v < e { (e, idx[i]) = (v, k) }
                }
                err += e
                i += 1
            }
            if err < best.err { best = (err, base, mult, t, idx) }
        }
        t += 1
    }
    var h = 0, l = 0
    for i in 0..<8 { h |= best.idx[i] << (21 - 3 * i) }
    for i in 8..<16 { l |= best.idx[i] << (45 - 3 * i) }
    out[o] = UInt8(best.base)
    out[o + 1] = UInt8(best.mult << 4 | best.t)
    for (k, v) in [h >> 16, h >> 8, h, l >> 16, l >> 8, l].enumerated() { out[o + 2 + k] = UInt8(v & 255) }
}

/// One 8-byte colour block from 16 pixels in block order.
private func rgbBlock(_ px: [UInt8], _ out: inout [UInt8], _ o: Int) {
    var bestErr = Int.max
    var first = [Bool](repeating: false, count: 16)
    for flip in 0..<2 {
        var sum = [[0, 0, 0], [0, 0, 0]]
        for i in 0..<16 {
            first[i] = flip == 1 ? i & 3 < 2 : i >> 2 < 2
            for k in 0..<3 { sum[first[i] ? 0 : 1][k] += Int(px[i * 4 + k]) }
        }
        let avg = sum.map { $0.map { Double($0) / 8 } }
        let q5 = avg.map { $0.map { jsRound($0 * 31 / 255) } }
        let d = (0..<3).map { q5[1][$0] - q5[0][$0] }
        let diff = d.allSatisfy { $0 >= -4 && $0 <= 3 }
        let q = diff ? q5 : avg.map { $0.map { jsRound($0 * 15 / 255) } }
        let bases = q.map { $0.map { diff ? $0 << 3 | $0 >> 2 : $0 * 17 } }
        var tables = [0, 0], idx = [Int](repeating: 0, count: 16), total = 0
        for half in 0..<2 {
            var tErr = Int.max
            var ks = [Int](repeating: 0, count: 16)
            for t in 0..<8 {
                var e = 0
                for i in 0..<16 where first[i] == (half == 0) {
                    let (k, err) = bestIdx(Int(px[i * 4]), Int(px[i * 4 + 1]), Int(px[i * 4 + 2]), bases[half], mod[t])
                    ks[i] = k
                    e += err
                }
                if e < tErr {
                    (tErr, tables[half]) = (e, t)
                    for i in 0..<16 where first[i] == (half == 0) { idx[i] = ks[i] }
                }
            }
            total += tErr
        }
        if total >= bestErr { continue }
        bestErr = total
        for k in 0..<3 { out[o + k] = UInt8(truncatingIfNeeded: diff ? q[0][k] << 3 | d[k] & 7 : q[0][k] << 4 | q[1][k]) }
        out[o + 3] = UInt8(tables[0] << 5 | tables[1] << 2 | (diff ? 2 : 0) | flip)
        var lo = 0
        for i in 0..<16 { lo |= (idx[i] >> 1) << (16 + i) | (idx[i] & 1) << i }
        for k in 0..<4 { out[o + 4 + k] = UInt8(truncatingIfNeeded: lo >> (24 - 8 * k)) }
    }
}

/// Best modifier index for one pixel against base ± table entry, and its error.
private func bestIdx(_ r: Int, _ g: Int, _ b: Int, _ base: [Int], _ t: [Int]) -> (Int, Int) {
    var best = 0, err = Int.max
    for k in 0..<4 {
        let m = (k & 1 != 0 ? t[1] : t[0]) * (k & 2 != 0 ? -1 : 1)
        let e = sq(clamp(base[0] + m) - r) + sq(clamp(base[1] + m) - g) + sq(clamp(base[2] + m) - b)
        if e < err { (best, err) = (k, e) }
    }
    return (best, err)
}
