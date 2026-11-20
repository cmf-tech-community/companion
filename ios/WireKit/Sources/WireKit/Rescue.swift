import Foundation

// Rescue for a watch stuck in a boot loop on a bad face (fmc_frontend rescue.model.ts, ble.ts connect({rescue}),
// authenticate, fetchInfo's time, factoryReset). Pure: the platform's BLE code calls `connected()` once notifications
// are on, `retry()` every 50 ms until the watch answers, and `notify` for every value on fff1, and writes what they
// return to fff2 at once. Everything that doesn't depend on the watch's nonce is sealed in `init`, before the watch is
// even in range.
//
// From the cmf-watch-utils emulator (stock 1.0.1.77): the watch drops every write until its own MTU exchange has
// brought the command service up (~200 ms after the connect), hence the retry; and it ignores 0x009A until a time
// (0x8004) has set its link flag. A clock more than 2 min off is applied on the second 0x8004 only, so it goes twice.
//
// Only a watch this app already holds keys for: the authkey of an earlier install or read (`stock-<id>`), else the
// pairing secret (`k-<id>`), with which a new bind needs no tap on the watch (the second 0x8049 is answered, cmf-watch-sdk
// docs/recon.md, "Bind popup…"). Without either there is no plan: nothing is read off a watch the app never knew.

public enum RescueAction: Equatable, Sendable {
    /// Gadgetbridge's FACTORY_RESET: c1 0x009A, c2 0x0001, the marker byte. The watch doesn't answer it.
    case factoryReset
    /// The dial-list set (`StockSession.setDials`): the watch keeps these ids it has and shows `ids[active]`.
    case keepDials([UInt32], active: Int)
}

public struct RescueWrite: Equatable, Sendable {
    public let uuid: String
    public let data: Data
    /// For the log: which command.
    public let what: String
}

public struct RescueStep: Equatable, Sendable {
    public var writes: [RescueWrite] = []
    public var note: String?
    /// The action went out on an authenticated session (or the dial set was acknowledged): stop.
    public var done = false
    /// The watch refused every key this app has: stop and say so.
    public var refused = false
}

public extension StockCmd {
    static let factoryReset: UInt32 = 0x009A_0001
}

public struct RescuePlan {
    public let action: RescueAction
    /// The time 0x8004 carries; a fixed one in the tests.
    public var clock: () -> (now: Date, tzSeconds: Int) = { (Date(), TimeZone.current.secondsFromGMT()) }
    /// The authkey in use. A bind replaces it: the caller saves it after `connected` / `notify`.
    public private(set) var k1: Data?
    let secret: Data?
    private var random1: Data
    private var session: Data?
    private var phase = Phase.idle
    /// Bound on this connection: a refusal now is not a stale key.
    private var bound = false
    private var parser = StockParser()
    /// Sealed ahead: with k1 (authName, nonce request), the bind request.
    private var nameFrames: [Data] = [], nonceFrames: [Data] = [], pairFrames: [Data] = []

    enum Phase { case idle, pair, name, nonce, confirm, sent }

    /// nil when there is nothing to authenticate with.
    public init?(action: RescueAction, k1: Data?, secret: Data?,
                 random1: Data = Data((0..<16).map { _ in UInt8.random(in: 0...255) })) {
        guard k1?.count == 16 || secret?.count == 16 else { return nil }
        (self.action, self.k1, self.secret, self.random1) = (action, k1?.count == 16 ? k1 : nil, secret, random1)
        prepare()
    }

    private mutating func prepare() {
        if let secret { pairFrames = stockFrames(StockCmd.authPairReq, pairRequest(random1: random1, secret: secret), key: nil) }
        if let k1 {
            nameFrames = stockFrames(StockCmd.authName, StockSession.phone, key: k1)
            nonceFrames = stockFrames(StockCmd.authNonceReq, Data([marker]), key: k1)
        }
    }

    /// The first writes on a new connection: authName with a known authkey, else the bind request.
    public mutating func connected() -> [RescueWrite] {
        parser = StockParser()
        session = nil
        bound = false
        if k1 != nil {
            phase = .name
            return w(nameFrames, "authName")
        }
        phase = .pair
        return w(pairFrames, "pair")
    }

    /// Again what the watch hasn't answered yet: it drops writes until its command service is up.
    public func retry() -> [RescueWrite] {
        switch phase {
        case .name: w(nameFrames, "authName")
        case .pair: w(pairFrames, "pair")
        default: []
        }
    }

    public mutating func notify(_ uuid: String, _ value: Data) -> RescueStep {
        guard uuid.uppercased() == Uuid.cmdNotify else { return RescueStep() }
        let got: (cmd: UInt32, payload: Data)?
        do {
            got = try parser.feed(value, key: session ?? k1)
        } catch StockParser.Failure.cannotOpen(StockCmd.authMac) {
            // someone confirmed the bind popup: the authkey is zero now, bind again (recon.md, "Bind popup…")
            var zero = StockParser()
            guard (try? zero.feed(value, key: Data(count: 16))) != nil, secret != nil else { return RescueStep() }
            return rebind("bind popup confirmed on the watch, binding again")
        } catch {
            return RescueStep(note: "unreadable frame \(value.prefix(11).hexString)")
        }
        guard let (cmd, p) = got else { return RescueStep() }
        let name = String(format: "%04X", cmd & 0xFFFF)
        switch (cmd, phase) {
        case (StockCmd.authFailed, .name):
            // the saved authkey is stale (Nothing X bound again since): bind with the secret, on this connection
            guard secret != nil, !bound else { return RescueStep(note: "A061: the watch refused the saved key", refused: true) }
            k1 = nil
            return rebind("A061: saved key refused, binding with the pairing secret")
        case (StockCmd.authFailed, .pair):
            return RescueStep(note: "A061: the watch refused the pairing secret", refused: true)
        case (StockCmd.authPairRep, .pair):
            guard let secret, let key = pairKey(random1: random1, reply: p, secret: secret) else {
                return RescueStep(note: "0048: watch signature mismatch", refused: true)
            }
            k1 = key
            bound = true
            prepare()
            phase = .name
            // the first 0x8049 after a bind only opens the popup; the second is answered, no tap needed
            return RescueStep(writes: w(nameFrames, "authName") + w(nameFrames, "authName"), note: "0048: bound")
        case (StockCmd.authMac, .name):
            phase = .nonce
            return RescueStep(writes: w(nonceFrames, "nonce"), note: "0049")
        case (StockCmd.authNonceRep, .nonce):
            guard let k1 else { return RescueStep() }
            let s = sessionKey(nonce: p, k1: k1)
            session = s
            phase = .confirm
            // the time and the action ride right behind the confirm: commands are handled in order
            return RescueStep(writes: w(stockFrames(StockCmd.authConfirmReq, Data([marker]), key: s), "confirm")
                + timeWrites(s) + timeWrites(s) + actionWrites(s), note: "004C")
        case (StockCmd.authConfirmRep, .confirm):
            phase = .sent
            // again on the confirmed session, in case the early one was dropped; a reset that lands twice is one reset
            return RescueStep(writes: timeWrites(session!) + actionWrites(session!), note: "0004: authenticated",
                              done: action == .factoryReset)
        case (StockCmd.wfInstalled, .sent), (StockCmd.wfInstalled, .confirm):
            return RescueStep(note: "A055 \(p.hexString)", done: true)
        default:
            return RescueStep(note: name)
        }
    }

    private mutating func rebind(_ note: String) -> RescueStep {
        random1 = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        prepare()
        phase = .pair
        return RescueStep(writes: w(pairFrames, "pair"), note: note)
    }

    func timeWrites(_ s: Data) -> [RescueWrite] {
        let c = clock()
        return w(stockFrames(StockCmd.time, stockTime(now: c.now, tzSeconds: c.tzSeconds), key: s), "time")
    }

    func actionWrites(_ s: Data) -> [RescueWrite] {
        switch action {
        case .factoryReset:
            return w(stockFrames(StockCmd.factoryReset, Data([marker]), key: s), "factoryReset")
        case .keepDials(let ids, let active):
            var p = Data([1, UInt8(active), UInt8(ids.count), 0])
            for id in ids { p.le(id) }
            return w(stockFrames(StockCmd.wfList, p, key: s), "keepDials")
        }
    }

    private func w(_ frames: [Data], _ what: String) -> [RescueWrite] {
        frames.map { RescueWrite(uuid: Uuid.cmdWrite, data: $0, what: what) }
    }
}

/// ble.ts fetchInfo's 0x8004: unix seconds ‖ the zone's offset in ms, big endian.
public func stockTime(now: Date, tzSeconds: Int) -> Data {
    var t = Data()
    t.be(UInt32(now.timeIntervalSince1970))
    t.be(UInt32(bitPattern: Int32(tzSeconds * 1000)))
    return t
}

/// The dials to keep for `.keepDials`: of the list the watch last reported, the stock ones (and the photo face's
/// FFFFFFFF), in order; the first stock one is shown. nil when that leaves no stock dial.
public func rescueKeep(listed: [UInt32], stock: Set<UInt32>) -> (ids: [UInt32], active: Int)? {
    let ids = listed.filter { stock.contains($0) || $0 == 0xFFFF_FFFF }
    guard let active = ids.firstIndex(where: { $0 != 0xFFFF_FFFF }) else { return nil }
    return (ids, active)
}
