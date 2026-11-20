import CoreBluetooth
import SwiftUI
import WireKit

// Rescue for a watch that restarts in a loop after a bad face (fmc_frontend /rescue, WireKit RescuePlan). The phone
// waits for the watch without pause and sends one command the moment the stock session is up, on every boot, until it
// lands. Only watches this app holds keys for (the authkey `stock-<id>`, the pairing secret `k-<id>` in the keychain),
// found by their CoreBluetooth identifier: nothing on the watch has to be tapped, and no other watch is touched.
//
// Speed: frames are sealed before the watch appears (RescuePlan); a pending connect to each known peripheral lets the
// controller connect on the first advertisement, with a scan beside it that connects again on any advertisement seen
// while not connected. On connect: only service FFF0, only FFF1/FFF2, notify on, then the plan's writes. No MTU (iOS
// exchanges it itself), no info or dial list. Every step is logged with ms since the connect.

@MainActor @Observable final class RescueModel {
    enum Phase: Equatable { case idle, waiting, connected, sent, done, refused }

    private(set) var phase = Phase.idle
    private(set) var log: [String] = []
    private var rescuer: Rescuer?

    /// Watches this app holds a key for, by CoreBluetooth identifier.
    static var knownWatches: [UUID] {
        Set(Keychain.accounts().compactMap { a -> UUID? in
            for p in ["stock-", "k-"] where a.hasPrefix(p) { return UUID(uuidString: String(a.dropFirst(p.count))) }
            return nil
        }).sorted { $0.uuidString < $1.uuidString }
    }

    /// The dials `Remove custom faces` keeps: the stock ones of the list the watch last reported.
    static var keep: (ids: [UInt32], active: Int)? {
        let listed = UserDefaults.standard.array(forKey: "faces-on-watch-all") as? [UInt32] ?? []
        return rescueKeep(listed: listed, stock: Set(DialSlot.stock.keys))
    }

    var running: Bool { [.waiting, .connected].contains(phase) }

    func start(_ action: RescueAction) {
        log = []
        var plans: [UUID: RescuePlan] = [:]
        for id in Self.knownWatches {
            let k1 = Keychain.get("stock-\(id.uuidString)").flatMap(Data.init(hex:))
            let secret = Keychain.get("k-\(id.uuidString)").flatMap(channelKey(secretHex:))
            if let plan = RescuePlan(action: action, k1: k1, secret: secret) {
                plans[id] = plan
                add("watch \(id.uuidString.prefix(8)): \(k1 != nil ? "saved key" : "pairing secret")")
            }
        }
        guard !plans.isEmpty else { return }
        phase = .waiting
        UIApplication.shared.isIdleTimerDisabled = true
        rescuer = Rescuer(plans: plans) { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
    }

    func stop() {
        rescuer?.stop()
        rescuer = nil
        UIApplication.shared.isIdleTimerDisabled = false
        if running { phase = .idle }
    }

    private func handle(_ e: Rescuer.Event) {
        switch e {
        case .log(let line): add(line)
        case .connected: if phase == .waiting { phase = .connected }
        case .dropped: if phase == .connected { phase = .waiting }
        case .key(let id, let k1): Keychain.set("stock-\(id.uuidString)", k1.map { String(format: "%02x", $0) }.joined())
        case .done: phase = .done; stop()
        case .sent: phase = .sent; stop()
        case .refused: phase = .refused; stop()
        }
    }

    private func add(_ line: String) {
        log.append(line)
        DevLog.shared.log("rescue " + line)
    }
}

/// CoreBluetooth on its own queue, so the main thread never sits between a notify and the next write.
final class Rescuer: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    enum Event { case log(String), connected, dropped, key(UUID, Data), sent, done, refused }

    private static let service = CBUUID(string: "FFF0")
    private static let write = CBUUID(string: Uuid.cmdWrite), notify = CBUUID(string: Uuid.cmdNotify)
    private let queue = DispatchQueue(label: "tools.fmc.companion.rescue", qos: .userInteractive)
    private var central: CBCentralManager!
    private var plans: [UUID: RescuePlan]
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var writeChar: [UUID: CBCharacteristic] = [:]
    private var connectedAt: [UUID: UInt64] = [:]
    private var advSeen: Set<UUID> = []
    private var stopped = false
    private let started = DispatchTime.now().uptimeNanoseconds
    private let emit: (Event) -> Void

    init(plans: [UUID: RescuePlan], emit: @escaping (Event) -> Void) {
        (self.plans, self.emit) = (plans, emit)
        super.init()
        central = CBCentralManager(delegate: self, queue: queue)
    }

    func stop() {
        queue.async { [self] in
            stopped = true
            if central.state == .poweredOn { central.stopScan() }
        }
        // writes without response still queued go out before the link is cut
        queue.asyncAfter(deadline: .now() + 1) { [self] in peripherals.values.forEach(central.cancelPeripheralConnection) }
    }

    private func ms(since t: UInt64) -> Int { Int((DispatchTime.now().uptimeNanoseconds - t) / 1_000_000) }
    private func say(_ id: UUID?, _ text: String) {
        var line = String(format: "%7.3f", Double(ms(since: started)) / 1000)
        if let id, let t = connectedAt[id] { line += " +\(ms(since: t))ms" }
        emit(.log(line + " " + text))
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        guard c.state == .poweredOn, !stopped else {
            if c.state != .poweredOn { say(nil, "bluetooth \(c.state.rawValue)") }
            return
        }
        for p in c.retrievePeripherals(withIdentifiers: Array(plans.keys)) {
            p.delegate = self
            peripherals[p.identifier] = p
            arm(p)
        }
        // the pending connect fires on an advertisement too, but only one the system looks for; the scan catches the rest
        c.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        say(nil, "waiting for \(peripherals.count) known watch(es), scanning")
    }

    private func arm(_ p: CBPeripheral) {
        guard !stopped else { return }
        connectedAt[p.identifier] = nil
        central.connect(p) // no timeout on iOS: the controller connects on the next advertisement
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any],
                        rssi: NSNumber) {
        guard plans[p.identifier] != nil, !stopped else { return }
        if !advSeen.contains(p.identifier) {
            advSeen.insert(p.identifier)
            say(p.identifier, "advertising, rssi \(rssi)")
        }
        if peripherals[p.identifier] == nil {
            p.delegate = self
            peripherals[p.identifier] = p
        }
        if p.state == .disconnected { arm(p) }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        connectedAt[p.identifier] = DispatchTime.now().uptimeNanoseconds
        advSeen.remove(p.identifier)
        emit(.connected)
        say(p.identifier, "connected")
        p.discoverServices([Self.service])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        say(p.identifier, "connect failed: \(error?.localizedDescription ?? "?")")
        arm(p)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        say(p.identifier, "dropped\(error.map { ": \($0.localizedDescription)" } ?? "")")
        writeChar[p.identifier] = nil
        emit(.dropped)
        arm(p) // the next boot
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == Self.service }) else {
            return say(p.identifier, "no FFF0 service \(error?.localizedDescription ?? "")")
        }
        say(p.identifier, "services")
        p.discoverCharacteristics([Self.write, Self.notify], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        let chars = s.characteristics ?? []
        guard let w = chars.first(where: { $0.uuid == Self.write }), let n = chars.first(where: { $0.uuid == Self.notify })
        else { return say(p.identifier, "no FFF1/FFF2") }
        writeChar[p.identifier] = w
        say(p.identifier, "characteristics, mtu \(p.maximumWriteValueLength(for: .withoutResponse) + 3)")
        p.setNotifyValue(true, for: n)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard c.isNotifying, var plan = plans[p.identifier] else {
            return say(p.identifier, "notify failed: \(error?.localizedDescription ?? "?")")
        }
        say(p.identifier, "notify on")
        let writes = plan.connected()
        plans[p.identifier] = plan
        send(p, writes)
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == Self.notify, let value = c.value, var plan = plans[p.identifier], !stopped else { return }
        let before = plan.k1
        let step = plan.notify(Uuid.cmdNotify, value)
        plans[p.identifier] = plan
        if let note = step.note { say(p.identifier, "← " + note) }
        // a bind replaced the authkey on the watch: the saved one is the new one now
        if let k1 = plan.k1, k1 != before { emit(.key(p.identifier, k1)) }
        send(p, step.writes)
        if step.refused {
            emit(.refused)
        } else if step.done {
            say(p.identifier, "action sent on an authenticated session")
            emit(plan.action == .factoryReset ? .sent : .done)
        }
    }

    private func send(_ p: CBPeripheral, _ writes: [RescueWrite]) {
        guard let ch = writeChar[p.identifier] else { return }
        for w in writes {
            let fits = w.data.count <= p.maximumWriteValueLength(for: .withoutResponse)
            p.writeValue(w.data, for: ch, type: fits && ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse)
            say(p.identifier, "→ \(w.what)")
        }
    }
}

struct RescueView: View {
    @State private var model = RescueModel()
    @State private var removeFaces = false
    @State private var confirming = false
    private let known = RescueModel.knownWatches
    private let keep = RescueModel.keep

    var body: some View {
        List {
            Section {
                Text("For a watch that restarts over and over after a watch face. The phone waits for the watch and sends one command the moment it can, on every restart, until it lands.")
            }
            if known.isEmpty {
                Section {
                    Label("This phone has no key for your watch", systemImage: "key.slash")
                        .font(.body.weight(.semibold))
                    Text("Rescue needs the key this app saves the first time it reads the watch or installs a face. Without it the watch would ask for a confirmation on its screen, which a stuck watch can't show. Try the phone that installed the face, or the fmc.tools rescue page in the browser that installed it.")
                }
            } else {
                Section {
                    Picker("Action", selection: $removeFaces) {
                        Text("Factory reset").tag(false)
                        if keep != nil { Text("Remove custom faces").tag(true) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    .disabled(model.running)
                } footer: {
                    Text(removeFaces
                         ? "Keeps only the built-in faces the watch last reported and shows the first one. Your data stays. Not yet tried on a stuck watch."
                         : "Erases everything on the watch and restarts it.")
                }
                Section {
                    if model.running {
                        Button("Stop", role: .cancel) { model.stop() }
                    } else {
                        Button(removeFaces ? "Remove custom faces" : "Factory reset", role: .destructive) { confirming = true }
                    }
                } footer: {
                    Text("Keep this screen open and the phone next to the watch.")
                }
            }
            if model.phase != .idle {
                Section {
                    HStack(spacing: 10) {
                        if model.running { ProgressView() }
                        Text(status)
                    }
                    .foregroundStyle(model.phase == .refused ? Color.red : Color.primary)
                }
            }
            if !model.log.isEmpty {
                Section {
                    Text(model.log.joined(separator: "\n"))
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                } header: {
                    HStack {
                        Text("Log")
                        Spacer()
                        Button("Copy log") { UIPasteboard.general.string = model.log.joined(separator: "\n") }
                            .font(.footnote)
                    }
                }
            }
        }
        .navigationTitle("Rescue a stuck watch")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { model.stop() }
        .alert(removeFaces ? "Remove the custom faces?" : "Erase the watch?", isPresented: $confirming) {
            Button(removeFaces ? "Remove" : "Erase", role: .destructive) {
                if removeFaces, let keep { model.start(.keepDials(keep.ids, active: keep.active)) } else { model.start(.factoryReset) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(removeFaces ? "The watch keeps only its built-in faces." : "All data on the watch is erased. This can't be undone.")
        }
    }

    private var status: LocalizedStringKey {
        switch model.phase {
        case .idle: ""
        case .waiting: "Waiting for the watch…"
        case .connected: "Connected, sending…"
        case .sent: "Sent. The watch should restart clean. If it still restarts in a loop, start again."
        case .done: "Done. The watch kept only its built-in faces."
        case .refused: "The watch refused the key this app has: it was paired again since. Rescue can't go on without a confirmation on the watch."
        }
    }
}
