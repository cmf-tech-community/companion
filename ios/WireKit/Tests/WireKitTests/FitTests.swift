import Foundation
import Testing
@testable import WireKit

/// Made by tools/face-fit-vectors.ts from fmc_frontend's own budget.ts, fit.ts, etc2.ts and bin.ts.
struct FitVectors: Decodable {
    struct Decoded: Decodable { let cf, w, h, bytes: Int }
    struct Etc2: Decodable { let name: String; let w, h: Int; let rgba, blocks: String }
    struct Memory: Decodable { let tag: UInt8; let bytes: Int; let fit: String }
    struct Over: Decodable { let tag: UInt8; let bytes: Int }
    struct Fit: Decodable { let hex: String?; let compressed: Int; let refuse: String?; let over: Over? }
    struct FaceV: Decodable {
        let name, hex, rebuild: String
        let memory: [Memory]
        let fit: [String: Fit]
    }
    let decoded: [Decoded]
    let etc2: [Etc2]
    let faces: [FaceV]

    static let shared: FitVectors = {
        let url = Bundle.module.url(forResource: "face-fit-vectors", withExtension: "json")!
        return try! JSONDecoder().decode(FitVectors.self, from: Data(contentsOf: url))
    }()
}

private let fitV = FitVectors.shared

@Test func decodedBytesPerFormat() {
    for d in fitV.decoded { #expect(decodedBytes(cf: d.cf, w: d.w, h: d.h) == d.bytes, "cf \(d.cf) \(d.w)x\(d.h)") }
}

@Test func etc2Blocks() {
    for e in fitV.etc2 { #expect(Data(encodeEtc2([UInt8](hex(e.rgba)), e.w, e.h)) == hex(e.blocks), "\(e.name)") }
}

@Test(arguments: fitV.faces.map(\.name)) func faceFit(_ name: String) throws {
    let f = fitV.faces.first { $0.name == name }!
    let bin = hex(f.hex)
    #expect(try buildBin(parseBin(bin)) == hex(f.rebuild))
    let face = try parseBin(bin)
    #expect(face.screens.map { screenMemory($0, face.resources) }.map { "\($0.bytes) \($0.fit.rawValue)" }
        == f.memory.map { "\($0.bytes) \($0.fit)" })
    for (device, want) in f.fit {
        let w3 = device == "watch_3_pro"
        let (out, compressed) = try fitForWatch(bin, watch3Pro: w3)
        #expect(compressed == want.compressed, "\(device)")
        #expect(out == want.hex.map(hex) ?? bin, "\(device)")
        let verdict = checkFace(out, watch3Pro: w3)
        if let over = want.over {
            #expect(verdict == .refuse(.screenOverBudget(aod: over.tag == 0x22, bytes: over.bytes)))
        } else {
            #expect(want.refuse == nil)
            if case .refuse = verdict { Issue.record("\(device): refused \(verdict)") }
        }
    }
}

@Test func watch3ProByName() {
    #expect(isWatch3Pro("CMF Watch 3 Pro-1A2B"))
    #expect(isWatch3Pro("cmf watch pro 3"))
    #expect(!isWatch3Pro("CMF Watch Pro 2-1A2B"))
    #expect(!isWatch3Pro(nil))
}
