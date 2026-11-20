import Foundation
import Testing
@testable import WireKit

/// Made by tools/rescue-vectors.ts from fmc_frontend's Watch.authenticate, Watch.pair, Watch.fetchInfo and
/// Watch.factoryReset: the frames by command, and what the scripted watch notified.
struct RescueVectors: Decodable {
    struct Talk: Decodable { let writes, notifies: [String: String] }
    struct Keep: Decodable { let ids: [UInt32]; let active: Int; let frames: [String] }
    let k1, secret, random1, bound_k1: String
    let now, tz_seconds: Int
    let known, bind: Talk
    let keep_dials: Keep

    static let shared: RescueVectors = {
        let url = Bundle.module.url(forResource: "rescue-vectors", withExtension: "json")!
        return try! JSONDecoder().decode(RescueVectors.self, from: Data(contentsOf: url))
    }()
}

private let rv = RescueVectors.shared

private func plan(_ action: RescueAction = .factoryReset, k1: String? = rv.k1, secret: String? = nil) -> RescuePlan {
    var p = RescuePlan(action: action, k1: k1.map(hex), secret: secret.map(hex), random1: hex(rv.random1))!
    p.clock = { (Date(timeIntervalSince1970: TimeInterval(rv.now)), rv.tz_seconds) }
    return p
}

private func hexes(_ w: [RescueWrite]) -> [String] { w.map(\.data.hexString) }
private func feed(_ p: inout RescuePlan, _ t: RescueVectors.Talk, _ n: String) -> RescueStep {
    p.notify(Uuid.cmdNotify, hex(t.notifies[n]!))
}

/// The site's frames, in the plan's order: the time twice and the reset right behind the confirm, both again on 0x0004.
private func expectConversation(_ p: inout RescuePlan, _ t: RescueVectors.Talk) {
    let f = t.writes
    #expect(hexes(feed(&p, t, "authMac").writes) == [f["nonce"]!])
    #expect(p.retry().isEmpty)
    #expect(hexes(feed(&p, t, "nonceReply").writes) == [f["confirm"]!, f["time"]!, f["time"]!, f["factoryReset"]!])
    let last = feed(&p, t, "confirmReply")
    #expect(hexes(last.writes) == [f["time"]!, f["factoryReset"]!])
    #expect(last.done && !last.refused)
}

@Test func rescueWithSavedKeyMatchesSite() {
    var p = plan()
    #expect(hexes(p.connected()) == [rv.known.writes["authName"]!])
    // the watch drops writes until its service is up: authName again until it answers
    #expect(hexes(p.retry()) == [rv.known.writes["authName"]!])
    expectConversation(&p, rv.known)
}

@Test func rescueBindsWithSecretWithoutTap() {
    var p = plan(k1: nil, secret: rv.secret)
    #expect(hexes(p.connected()) == [rv.bind.writes["pair"]!])
    #expect(hexes(p.retry()) == [rv.bind.writes["pair"]!])
    // the first authName after a bind opens the popup, the second is answered: two, no tap
    #expect(hexes(feed(&p, rv.bind, "pairReply").writes) == Array(repeating: rv.bind.writes["authName"]!, count: 2))
    #expect(p.k1 == hex(rv.bound_k1))
    expectConversation(&p, rv.bind)
}

@Test func rescueStaleKeyFallsBackToSecret() {
    let failed = stockFrames(StockCmd.authFailed, Data([1, 2]), key: nil)[0]
    var p = plan(secret: rv.secret)
    _ = p.connected()
    let s = p.notify(Uuid.cmdNotify, failed)
    #expect(s.writes.map(\.what) == ["pair"] && !s.refused && p.k1 == nil)
    // without a secret a refused key ends the rescue: nothing to bind with
    var only = plan()
    _ = only.connected()
    #expect(only.notify(Uuid.cmdNotify, failed).refused)
}

@Test func rescueNeedsAKey() {
    #expect(RescuePlan(action: .factoryReset, k1: nil, secret: nil) == nil)
}

@Test func rescueKeepDialsMatchesSiteCodec() {
    var p = plan(.keepDials(rv.keep_dials.ids, active: rv.keep_dials.active))
    _ = p.connected()
    _ = feed(&p, rv.known, "authMac")
    let w = feed(&p, rv.known, "nonceReply").writes.filter { $0.what == "keepDials" }
    #expect(hexes(w) == rv.keep_dials.frames)
}

@Test func rescueKeepsStockDials() {
    let r = rescueKeep(listed: [0xFFFF_FFFF, 12345, 273, 280, 99999], stock: [273, 280])
    #expect(r?.ids == [0xFFFF_FFFF, 273, 280] && r?.active == 1)
    #expect(rescueKeep(listed: [12345], stock: [273]) == nil)
}
