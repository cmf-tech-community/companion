import Foundation
import Testing
@testable import WireKit

/// Made by tools/rescue-vectors.ts from fmc_frontend's Watch.authenticate, Watch.pair and Watch.factoryReset.
struct RescueVectors: Decodable {
    struct Step: Decodable { let notify: String?; let writes: [String] }
    struct Keep: Decodable { let ids: [UInt32]; let active: Int; let frames: [String] }
    let k1, secret, random1, bound_k1: String
    let known, bind: [Step]
    let keep_dials: Keep

    static let shared: RescueVectors = {
        let url = Bundle.module.url(forResource: "rescue-vectors", withExtension: "json")!
        return try! JSONDecoder().decode(RescueVectors.self, from: Data(contentsOf: url))
    }()
}

private let rv = RescueVectors.shared

/// Replays the site's conversation: each step's writes are a prefix of ours (we may add the action behind the confirm
/// and a second authName after a bind), and we write nothing the site doesn't.
private func replay(_ plan: inout RescuePlan, _ steps: [RescueVectors.Step]) -> RescueStep {
    let all = Set(steps.flatMap(\.writes))
    var last = RescueStep()
    for (i, s) in steps.enumerated() {
        let ours: [RescueWrite]
        if let n = s.notify {
            last = plan.notify(Uuid.cmdNotify, hex(n))
            ours = last.writes
        } else {
            ours = plan.connected()
        }
        #expect(ours.prefix(s.writes.count).map(\.data.hexString) == s.writes, "step \(i)")
        #expect(ours.allSatisfy { all.contains($0.data.hexString) && $0.uuid == Uuid.cmdWrite }, "step \(i)")
    }
    return last
}

@Test func rescueWithSavedKeyMatchesSite() {
    var plan = RescuePlan(action: .factoryReset, k1: hex(rv.k1), secret: nil)!
    let last = replay(&plan, rv.known)
    #expect(last.done && !last.refused)
}

@Test func rescueBindsWithSecretWithoutTap() {
    var plan = RescuePlan(action: .factoryReset, k1: nil, secret: hex(rv.secret), random1: hex(rv.random1))!
    #expect(replay(&plan, rv.bind).done)
    #expect(plan.k1 == hex(rv.bound_k1))
}

@Test func rescueSendsResetBehindConfirm() {
    var plan = RescuePlan(action: .factoryReset, k1: hex(rv.k1), secret: nil)!
    _ = plan.connected()
    _ = plan.notify(Uuid.cmdNotify, hex(rv.known[1].notify!))
    let w = plan.notify(Uuid.cmdNotify, hex(rv.known[2].notify!)).writes.map(\.what)
    #expect(w == ["confirm", "factoryReset"])
}

@Test func rescueStaleKeyFallsBackToSecret() {
    var plan = RescuePlan(action: .factoryReset, k1: hex(rv.k1), secret: hex(rv.secret), random1: hex(rv.random1))!
    _ = plan.connected()
    let failed = stockFrames(StockCmd.authFailed, Data([1, 2]), key: nil)[0]
    let s = plan.notify(Uuid.cmdNotify, failed)
    #expect(s.writes.map(\.what) == ["pair"] && !s.refused && plan.k1 == nil)
    // without a secret a refused key ends the rescue: nothing to bind with
    var only = RescuePlan(action: .factoryReset, k1: hex(rv.k1), secret: nil)!
    _ = only.connected()
    #expect(only.notify(Uuid.cmdNotify, failed).refused)
}

@Test func rescueNeedsAKey() {
    #expect(RescuePlan(action: .factoryReset, k1: nil, secret: nil) == nil)
}

@Test func rescueKeepDialsMatchesSiteCodec() {
    var plan = RescuePlan(action: .keepDials(rv.keep_dials.ids, active: rv.keep_dials.active), k1: hex(rv.k1), secret: nil)!
    _ = plan.connected()
    _ = plan.notify(Uuid.cmdNotify, hex(rv.known[1].notify!))
    let w = plan.notify(Uuid.cmdNotify, hex(rv.known[2].notify!)).writes.filter { $0.what == "keepDials" }
    #expect(w.map(\.data.hexString) == rv.keep_dials.frames)
}

@Test func rescueKeepsStockDials() {
    let r = rescueKeep(listed: [0xFFFF_FFFF, 12345, 273, 280, 99999], stock: [273, 280])
    #expect(r?.ids == [0xFFFF_FFFF, 273, 280] && r?.active == 1)
    #expect(rescueKeep(listed: [12345], stock: [273]) == nil)
}
