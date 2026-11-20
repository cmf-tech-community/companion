import SwiftUI
import WireKit

@main
struct FMCCompanionApp: App {
    @UIApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var account = Account()
    @State private var watch = WatchModel.shared
    @State private var catalog = Catalog()
    // DEBUG screenshots: `-watch` opens the Watch tab, `-apps` Apps, `-app <name>` that app's page
    @State private var tab = CommandLine.arguments.contains("-watch") ? 1
        : CommandLine.arguments.contains(where: { $0 == "-apps" || $0 == "-app" }) ? 2 : 0
    /// The catalog's navigation: face ids. A link to a face replaces it.
    @State private var path: [String] = []

    init() {
        Analytics.start()
        // the catalog's pictures: fmc.tools sends them with max-age 30 days, so one seen is read from disk after
        URLCache.shared = URLCache(memoryCapacity: 50 << 20, diskCapacity: 300 << 20)
    }

    var body: some Scene {
        WindowGroup {
            TabView(selection: $tab) {
                CatalogView(path: $path)
                    .tabItem { Label("Watch faces", systemImage: "square.grid.2x2") }
                    .tag(0)
                AppsTab()
                    .tabItem { Label("Apps", systemImage: "square.grid.3x3.fill") }
                    .badge(Permissions.shared.pending.count)
                    .tag(2)
                ContentView()
                    .tabItem { Label("Watch", systemImage: "applewatch") }
                    .tag(1)
            }
            .onOpenURL(perform: open)
            .onChange(of: Permissions.shared.open) { if $1 != nil { tab = 2 } }
            .environment(account)
            .environment(watch)
            .environment(catalog)
            .tint(.fmcAccent)
            .task { await account.restore() }
            #if DEBUG
            // `-check` reads the watch on launch
            .task { if CommandLine.arguments.contains("-check") { await watch.check() } }
            // `-open <link>`: what onOpenURL gets, without the system's "Open in" prompt
            .task {
                let a = CommandLine.arguments
                if let i = a.firstIndex(of: "-open"), i + 1 < a.count, let url = URL(string: a[i + 1]) { open(url) }
            }
            #endif
        }
    }
}

extension FMCCompanionApp {
    func open(_ url: URL) {
        guard let id = FaceLink.watchface(url) else { return }
        tab = 0
        path = [id]
    }
}

struct ContentView: View {
    @Environment(Account.self) private var account
    @Environment(WatchModel.self) private var watch
    /// One path for every page the Watch tab pushes: links by value only, mixed link styles push twice.
    @State private var path: [Route] = Self.launchPath

    enum Route: Hashable { case firmware, rescue, developer, wakeProbe, signIn }

    // DEBUG screenshots: `-signin` opens Sign in
    private static var launchPath: [Route] { CommandLine.arguments.contains("-signin") ? [.signIn] : [] }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    WatchStatus()
                        // the watch is usually there: read it on opening, as the Apps tab does
                        .task { if watch.phase == .idle { await watch.check() } }
                        // a smaller top than the row's own 11
                        .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 11, trailing: 20))
                } footer: {
                    Text("Keep the watch connected in Nothing X. The app only reads from it.")
                }
                // faces and firmware are the watch's: only while it is connected
                if case .checked = watch.phase {
                    FacesOnWatchSection()
                }
                Section {
                    if case .checked = watch.phase {
                        NavigationLink(value: Route.firmware) {
                            HStack(spacing: 12) {
                                SettingsIcon(symbol: "cpu.fill", color: .gray)
                                Text("Firmware")
                                Spacer()
                                CountBadge(count: FirmwareModel.shared.updates(watch.loader))
                            }
                        }
                    }
                    // the app's own page in Settings has the per-app Language picker
                    Link(destination: URL(string: UIApplication.openSettingsURLString)!) {
                        HStack(spacing: 12) {
                            SettingsIcon(symbol: "globe", color: .blue)
                            Text("Language")
                            Spacer()
                            Text(Locale.current.localizedString(forLanguageCode: Locale.current.language.languageCode?.identifier ?? "")?.localizedCapitalized ?? "")
                                .foregroundStyle(.secondary)
                            Image(systemName: "arrow.up.forward").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                        }
                    }
                    .tint(.primary)
                    // reachable without a connected watch: it is for one that can't connect
                    NavigationLink(value: Route.rescue) {
                        HStack(spacing: 12) {
                            SettingsIcon(symbol: "cross.case.fill", color: .red)
                            Text("Rescue a stuck watch")
                        }
                    }
                    NavigationLink(value: Route.developer) {
                        HStack(spacing: 12) {
                            SettingsIcon(symbol: "hammer.fill", color: .gray)
                            Text("Developer")
                        }
                    }
                }
                Section("FMC account") {
                    if let user = account.user {
                        HStack(spacing: 14) {
                            AsyncImage(url: user.avatarURL) { $0.resizable().scaledToFill() } placeholder: {
                                Image(systemName: "person.fill").font(.title3).foregroundStyle(Color.fmcOnAccent)
                            }
                            .frame(width: 44, height: 44)
                            .background(Color.fmcAccent)
                            .clipShape(.circle)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(user.name.isEmpty ? "Signed in" : user.name).font(.body.weight(.semibold))
                                Text(user.email).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                        Button("Sign out") { account.signOut() }
                        DeleteAccountButton()
                    } else {
                        Button { path.append(.signIn) } label: {
                            LabeledContent {
                                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                            } label: {
                                Label("Sign in", systemImage: "person.crop.circle")
                            }
                        }
                        .tint(.primary)
                    }
                }
            }
            .navigationTitle("FMC Companion")
            // updates are checked right after each connect: the Firmware row shows them
            .task(id: watch.phase) { if case .checked = watch.phase { await FirmwareModel.shared.refresh(account) } }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .firmware: FirmwareView()
                case .rescue: RescueView()
                case .developer: DeveloperView()
                case .wakeProbe:
                    #if DEBUG
                    WakeProbeView()
                    #else
                    EmptyView()
                    #endif
                case .signIn: SignInView()
                }
            }
        }
    }
}

/// The watch tile from workout-companion's WatchView, with Zeron's capsule button under it.
struct WatchStatus: View {
    @Environment(WatchModel.self) private var watch
    /// changes with every read of the faces: the active one is redrawn
    @AppStorage("faces-on-watch-header") private var header = ""

    /// The face on screen as last read.
    private var active: DialSlot? {
        _ = header
        guard let id = FaceInstall.activeDial else { return nil }
        let ids = UserDefaults.standard.array(forKey: "faces-on-watch") as? [UInt32] ?? []
        return DialSlot(id: id, index: ids.firstIndex(of: id) ?? 0)
    }

    var body: some View {
        // the bezel is the layout: the same gap above it and to the button; case and strap get cut by both
        VStack(spacing: 8) {
            HStack(spacing: 16) {
                WatchFrame(side: 76) {
                    // the face on screen once connected; till then what the app is doing
                    switch watch.phase {
                    case .checking: ProgressView().tint(.white)
                    case .checked: if let active { DialPicture(slot: active) } else { Color.black }
                    case .idle, .failed:
                        Image(systemName: "antenna.radiowaves.left.and.right.slash")
                            .font(.system(size: 22, weight: .medium)).foregroundStyle(.white.opacity(0.6))
                    }
                }
                // the bezel lines up with the button's left edge; the case hangs out past it
                .padding(.trailing, 16)
                VStack(alignment: .leading, spacing: 2) {
                    // one line each: a taller row would move the watch
                    Text(title).font(.title3.bold()).lineLimit(1).minimumScaleFactor(0.8)
                    Text(detail).font(.subheadline).foregroundStyle(detailColor).lineLimit(1).minimumScaleFactor(0.8)
                }
                Spacer(minLength: 0)
            }
            Button {
                Task { await watch.check() }
            } label: {
                Label(watch.phase == .idle ? "Connect" : "Check again",
                      systemImage: watch.phase == .idle ? "dot.radiowaves.left.and.right" : "arrow.clockwise")
            }
            .buttonStyle(.primary)
            .disabled(watch.phase == .checking)
            // opaque while disabled: the strap runs under it
            .background(Color(.secondarySystemGroupedBackground), in: .capsule)
        }
    }

    private var connected: Bool { if case .checked = watch.phase { true } else { false } }

    private var title: LocalizedStringKey {
        if watch.installing { return "Updating" }
        return switch watch.phase {
        case .idle: "Not connected"
        case .checking: "Connecting…"
        case .checked: "Connected"
        case .failed: "Couldn't connect"
        }
    }

    private var detail: String {
        if watch.installing { return String(localized: "The watch is installing the update…") }
        return switch watch.phase {
        case .idle: String(localized: "Tap Connect to read it")
        case .checking: String(localized: "This takes a few seconds")
        case .checked(let state): state == nil ? String(localized: "Ready for watch faces") : String(localized: "App Loader found")
        case .failed(let message): message
        }
    }

    private var detailColor: Color {
        if watch.installing { return .secondary }
        return switch watch.phase {
        case .checked: .fmcSuccess
        case .failed: .fmcWarning
        default: .secondary
        }
    }
}

/// The watch's faces as last read: by the button (stock session, Nothing X closed) or by an install.
struct FacesOnWatchSection: View {
    @Environment(Account.self) private var account
    @Environment(Catalog.self) private var catalog
    @Environment(WatchModel.self) private var watch
    @State private var faces: [DialSlot] = []
    @State private var date: Date?
    /// [count, capacity] from the a055 header, side-loaded dials included
    @State private var count: [Int]?
    @State private var header = ""
    @State private var reading = false
    @State private var error: String?
    /// No usable stock key: reading the list needs a bind, which only the user starts.
    @State private var busy = false
    @State private var deleting: DialSlot?

    var body: some View {
        Section {
            if !faces.isEmpty {
                // as Nothing X and fmc.tools show them: the round previews in the watch's own order
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(faces) { slot in
                            // a Menu, not .contextMenu: in a List row that one lifts the whole row, not this face
                            Menu {
                                Button("Show on the watch", systemImage: "applewatch") {
                                    Task { await change { try await FaceInstall.activate(slot.id) } }
                                }
                                // the menu's .primary tint reaches the icon too: red for this one
                                Button("Delete", systemImage: "trash", role: .destructive) { deleting = slot }
                                    .tint(.red)
                            } label: {
                                DialTile(slot: slot, active: slot.id == active)
                            } primaryAction: {
                                Task { await change { try await FaceInstall.activate(slot.id) } }
                            }
                            // the app's yellow tint would color the menu: system colors there, red for Delete
                            .tint(.primary)
                            .buttonStyle(.plain)
                            .disabled(busy)
                            .opacity(busy ? 0.4 : 1)
                        }
                    }
                }
                .scrollIndicators(.visible)
                // edge to edge in the row, the first face still lined up with the text
                .contentMargins(.horizontal, 20, for: .scrollContent)
                .contentMargins(.bottom, 10, for: .scrollContent)
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                .padding(.vertical, 6)
            }
            if reading {
                HStack { Text("Reading the watch…").foregroundStyle(.secondary); Spacer(); ProgressView() }
            } else if faces.isEmpty {
                Text("Connect to see the faces on the watch.").foregroundStyle(.secondary)
            }
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        } header: {
            if let count, count.count == 2 { Text("Watch faces · \(count[0]) of \(count[1])") } else { Text("Watch faces") }
        }
        // the catalog loads with the Watch faces tab; this one can come first
        .task(id: faces.map(\.id)) { await catalog.resolve(faces.map(\.id), account: account) }
        .onAppear(perform: load)
        // right after each connect, with the saved key only: a bind takes the watch from Nothing X
        .task(id: watch.phase) { if case .checked = watch.phase { await read(bind: false) } }
        .alert("Delete \(deleting?.label ?? "")?", isPresented: Binding { deleting != nil } set: { if !$0 { deleting = nil } }) {
            Button("Delete", role: .destructive) {
                if let id = deleting?.id { Task { await change { try await FaceInstall.delete(id) } } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It goes from the watch. To get it back, install it again.")
        }
    }

    private func read(bind: Bool, retry: Bool = true) async {
        reading = true
        defer { reading = false }
        do {
            try await FaceInstall.read(bind: bind)
            error = nil
            load()
            DevLog.shared.log("faces: \(faces.count) read, header \(header.isEmpty ? "none" : header)")
        } catch WireError.timeout where !bind && retry {
            // right after a connect or Bluetooth coming back the watch can be slow to answer: once more
            DevLog.shared.log("faces: no answer, again")
            await read(bind: false, retry: false)
        } catch UploadError.authFailed where !bind, WireError.timeout where !bind {
            DevLog.shared.log("faces: \(error is UploadError ? "the saved stock key was refused" : "no answer"), the list stays as last read")
        } catch {
            DevLog.shared.log("faces: \(error.localizedDescription)")
            self.error = String(localized: "Couldn't read the faces on the watch: \(error.localizedDescription)")
        }
    }

    /// The face on screen: a055 byte 1 (cmf-watch-sdk docs/recon.md, "Stock watch faces"), as of the last read.
    private var active: UInt32? { _ = header; return FaceInstall.activeDial }

    /// A change to the list on the watch, then the list again.
    private func change(_ op: () async throws -> Void) async {
        busy = true
        defer { busy = false }
        do {
            try await op()
            error = nil
            load()
        } catch {
            self.error = String(localized: "Couldn't change the faces on the watch: \(error.localizedDescription)")
        }
    }

    private func load() {
        let ids = UserDefaults.standard.array(forKey: "faces-on-watch") as? [UInt32] ?? []
        faces = ids.enumerated().map { DialSlot(id: $1, index: $0) }
        date = UserDefaults.standard.object(forKey: "faces-on-watch-date") as? Date
        count = UserDefaults.standard.array(forKey: "faces-on-watch-count") as? [Int]
        header = UserDefaults.standard.string(forKey: "faces-on-watch-header") ?? ""
    }
}

/// Zeron's welcome page (mark, title, tagline, capsule buttons) around a grouped section for the fields.
/// A List, not a ScrollView: a TextField in a ScrollView loops layout on iOS 27.
/// A face on the watch as Nothing X and fmc.tools show it: its round preview and name; the active one ringed yellow.
struct DialTile: View {
    @Environment(Catalog.self) private var catalog
    let slot: DialSlot
    var active = false

    var body: some View {
        let face = slot.face(in: catalog)
        VStack(spacing: 6) {
            DialPicture(slot: slot)
            .padding(3)
            .overlay { if active { Circle().strokeBorder(Color.fmcAccent, lineWidth: 2.5) } }
            Text(face?.name ?? slot.label).font(.caption.weight(.semibold)).lineLimit(1)
        }
        .frame(width: 84)
    }
}

/// A face's round picture: its preview, else the black panel with its number.
struct DialPicture: View {
    @Environment(Catalog.self) private var catalog
    let slot: DialSlot

    var body: some View {
        if let url = slot.face(in: catalog)?.thumbURL ?? slot.savedPreview {
            FacePreview(url: url)
        } else {
            // no picture anywhere for stock and Nothing X faces: the watch's place for it
            Circle().fill(.black).aspectRatio(1, contentMode: .fit).overlay {
                Text("\(slot.index + 1)").font(.system(.title, design: .rounded, weight: .bold))
                    .foregroundStyle(Color.fmcAccent)
            }
        }
    }
}

/// The CMF Watch Pro 2 render fmc.tools frames dials with (fmc_frontend watch-case.svelte), `content` on its glass.
/// Laid out as the bezel alone (`side` across): the case and strap hang outside it and take no space. The render is
/// square, the bezel 51.75% of it, centred a hair under the middle; the panel 94% of the bezel.
struct WatchFrame<Content: View>: View {
    var side: CGFloat
    @ViewBuilder let content: Content

    var body: some View {
        let render = side / 0.5175
        content
            .frame(width: side * 0.94, height: side * 0.94)
            .clipShape(.circle)
            .frame(width: side, height: side)
            .background(Circle().fill(.black))
            .background {
                Image("WatchFrame").resizable().frame(width: render, height: render)
                    .offset(y: -render * 0.00175)
                    .allowsHitTesting(false)
            }
            .accessibilityHidden(true)
    }
}

struct SignInView: View {
    @Environment(Account.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Badge(side: 64) { Image(systemName: "applewatch").font(.system(size: 30, weight: .medium)) }
                        .padding(.bottom, 14)
                    Text("Sign in to FMC").font(.title.weight(.semibold))
                    Text("Your fmc.tools account, for watch faces on your watch.")
                        .font(.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .plainRow()
            }
            Section {
                TextField("Email", text: $email)
                    .textContentType(.username)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(.password)
            } footer: {
                if let error {
                    Label(error, systemImage: "exclamationmark.triangle.fill").foregroundStyle(Color.fmcDanger)
                }
            }
            Section {
                VStack(spacing: 12) {
                    Button {
                        run { try await account.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password) }
                    } label: {
                        if busy { ProgressView().tint(.fmcOnAccent) } else { Text("Sign in") }
                    }
                    .buttonStyle(.primary)
                    .disabled(busy || email.isEmpty || password.isEmpty)

                    if !account.providers.isEmpty {
                        HStack {
                            VStack { Divider() }
                            Text("or").font(.subheadline).foregroundStyle(.secondary)
                            VStack { Divider() }
                        }
                        .padding(.vertical, 4)
                        // Apple first, as 4.8 wants it offered alongside, and black/white as the HIG asks.
                        ForEach(account.providers.sorted { $0.name == "apple" && $1.name != "apple" }) { p in
                            let apple = p.name == "apple"
                            let button = Button {
                                run { try await account.signIn(with: p) }
                            } label: {
                                Label("Continue with \(p.displayName)", systemImage: apple ? "apple.logo" : "g.circle.fill")
                            }
                            .disabled(busy)
                            if apple { button.buttonStyle(.primary) } else { button.buttonStyle(.secondary) }
                        }
                    }

                    Link("No account? Sign up at fmc.tools", destination: Account.signUp)
                        .font(.subheadline)
                        .padding(.top, 8)
                }
                .plainRow()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await account.loadProviders() }
    }

    private func run(_ action: @escaping () async throws -> Void) {
        Task {
            busy = true
            defer { busy = false }
            do {
                try await action()
                dismiss()
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

struct DeleteAccountButton: View {
    @Environment(Account.self) private var account
    @State private var asking = false
    @State private var error: String?

    var body: some View {
        Button("Delete account", role: .destructive) { asking = true }
            .confirmationDialog("Delete your FMC account?", isPresented: $asking, titleVisibility: .visible) {
                Button("Delete account", role: .destructive) {
                    Task {
                        do { try await account.deleteAccount() } catch { self.error = error.localizedDescription }
                    }
                }
            } message: {
                Text("This removes your account and profile on fmc.tools. It can't be undone.")
            }
            .alert("Couldn't delete the account", isPresented: .constant(error != nil)) {
                Button("OK") { error = nil }
            } message: {
                Text(error ?? "")
            }
    }
}

private extension View {
    /// A List row without the card behind it, for content that sits on the page.
    func plainRow() -> some View {
        listRowBackground(Color.clear).listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
    }
}
