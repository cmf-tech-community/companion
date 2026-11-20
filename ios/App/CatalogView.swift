import MarkdownUI
import SwiftUI
import WireKit

/// The FMC catalog and, signed in, the user's own faces (fmc_frontend market.svelte and profile.svelte):
/// a grid of round previews, search by name or author.
struct CatalogView: View {
    @Environment(Account.self) private var account
    @Environment(Catalog.self) private var catalog
    @Binding var path: [String]
    @State private var shelf: Shelf = CommandLine.arguments.contains("-mine") ? .mine : .all
    @State private var search = ""
    @AppStorage("catalog-sort") private var sort: CatalogSort = .new

    private var mine: Bool { shelf == .mine }
    /// What the grid shows, as the server is asked for it.
    private var query: CatalogQuery {
        CatalogQuery(shelf: account.user == nil ? .all : shelf, sort: sort, search: search, user: account.user?.id)
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                if account.user != nil {
                    Picker("Show", selection: $shelf) {
                        Text("All").tag(Shelf.all)
                        Text("Liked").tag(Shelf.liked)
                        Text("Mine").tag(Shelf.mine)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                }
                grid
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(mine ? "My watch faces" : shelf == .liked ? "Liked" : "Watch faces")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sort", selection: $sort) {
                            Text("Newest").tag(CatalogSort.new)
                            Text("Popular").tag(CatalogSort.popular)
                            Text("Most downloaded").tag(CatalogSort.downloads)
                        }
                    } label: {
                        Label("Sort", systemImage: "arrow.up.arrow.down")
                    }
                }
            }
            .searchable(text: $search, prompt: "Name or author")
            .refreshable { await catalog.refresh(account: account) }
            .task(id: account.user?.id) { await catalog.loadMyLikes(account) }
            .task(id: query) {
                // typing: the server is asked once the user pauses, not per letter
                if query.search != catalog.query.search { try? await Task.sleep(for: .milliseconds(350)) }
                guard !Task.isCancelled else { return }
                await catalog.show(query, account: account)
            }
            // ponytail: one String path for both pages, "user:<id>" for an author; a typed route if more come
            .navigationDestination(for: String.self) { v in
                if v.hasPrefix("user:") { AuthorView(id: String(v.dropFirst(5))) } else { FaceDetail(id: v) }
            }
        }
    }

    @ViewBuilder private var grid: some View {
        let faces = catalog.faces
        if faces.isEmpty {
            ContentUnavailableView {
                Label(empty, systemImage: "square.grid.2x2")
            } description: {
                if let error = catalog.error { Text(error) }
            }
            .padding(.top, 80)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                ForEach(Array(faces.enumerated()), id: \.element.id) { i, face in
                    NavigationLink(value: face.id) { FaceCell(face: face) }
                        .buttonStyle(.plain)
                        .onAppear {
                            if CatalogQuery.nearEnd(i, of: faces.count) { Task { await catalog.more(account: account) } }
                        }
                }
            }
            .padding()
            if catalog.loading && !catalog.end { ProgressView().padding(.bottom) }
        }
    }

    private var empty: LocalizedStringKey {
        if catalog.error != nil { "Couldn't load watch faces" }
        else if catalog.loading { "Loading…" }
        else if !search.isEmpty { "Nothing found" }
        else if mine { "No watch faces yet" }
        else if shelf == .liked { "No liked watch faces" }
        else { "Loading…" }
    }
}

/// As fmc.tools' watchface-card: the dial, its name, the author, then downloads, likes and the type.
struct FaceCell: View {
    @Environment(Catalog.self) private var catalog
    @Environment(Account.self) private var account
    let face: Watchface

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FacePreview(url: face.thumbURL).padding(.bottom, 2)
            Text(face.name).font(.subheadline.weight(.semibold)).lineLimit(1)
            if !face.published {
                Text("Draft").font(.caption).foregroundStyle(.secondary)
            } else if let author = face.author {
                HStack(spacing: 6) {
                    Avatar(url: face.authorAvatar, name: author, side: 18)
                    Text(author).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            HStack(spacing: 12) {
                Label(compact(face.downloads), systemImage: "arrow.down.circle")
                LikeButton(face: face)
                Spacer(minLength: 0)
                if let type = face.type, !type.isEmpty {
                    Text(type).font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color(.tertiarySystemFill), in: .capsule)
                }
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
        .padding(10)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 18, style: .continuous))
    }
}

/// A creator's page, as fmc.tools /user/<id>: avatar, name, totals, then their published faces.
struct AuthorView: View {
    @Environment(Catalog.self) private var catalog
    @Environment(Account.self) private var account
    let id: String
    @State private var faces: [Watchface] = []

    var body: some View {
        let owner = faces.first?.expand?.owner
        ScrollView {
            VStack(spacing: 8) {
                Avatar(url: owner?.avatarURL, name: owner?.name ?? "?", side: 80)
                Text(owner?.name ?? "").font(.title2.bold())
                Text("\(faces.count) watch faces · \(compact(faces.map(\.downloads).reduce(0, +))) downloads · \(compact(faces.map(catalog.likeCount).reduce(0, +))) likes")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            .padding(.top, 8)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                ForEach(faces) { face in
                    NavigationLink(value: face.id) { FaceCell(face: face) }.buttonStyle(.plain)
                }
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(owner?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Link(destination: Account.base.appending(path: "user/\(id)")) { Label("Open on fmc.tools", systemImage: "safari") }
            }
        }
        .task { if let f = try? await catalog.author(id, account: account) { faces = f } }
    }
}

/// The heart and the count; a tap likes or unlikes when signed in.
struct LikeButton: View {
    @Environment(Catalog.self) private var catalog
    @Environment(Account.self) private var account
    let face: Watchface

    var body: some View {
        let liked = catalog.liked(face)
        Button {
            Task { await catalog.toggleLike(face, account: account) }
        } label: {
            Label(compact(catalog.likeCount(face)), systemImage: liked ? "heart.fill" : "heart")
                .foregroundStyle(liked ? Color.red : Color.secondary)
        }
        .buttonStyle(.plain)
        .disabled(account.user == nil)
    }
}

/// A round avatar, else the name's first letter.
struct Avatar: View {
    let url: URL?
    let name: String
    var side: CGFloat = 24

    var body: some View {
        AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: {
            Text(name.prefix(1).uppercased()).font(.system(size: side * 0.5, weight: .semibold))
                .foregroundStyle(Color.fmcOnAccent)
        }
        .frame(width: side, height: side)
        .background(Color.fmcAccent)
        .clipShape(.circle)
    }
}

/// 1234 → "1.2K", as fmc.tools' compact().
func compact(_ n: Int) -> String {
    n.formatted(.number.notation(.compactName).precision(.fractionLength(0...1)))
}

/// The watch's 466×466 round panel.
struct FacePreview: View {
    let url: URL?

    var body: some View {
        // URLCache.shared, sized at launch (FMCCompanionApp): the files say max-age 30 days, so a picture seen once
        // comes from disk after that
        AsyncImage(url: url) { image in
            image.resizable().scaledToFit()
        } placeholder: {
            Color(.tertiarySystemGroupedBackground)
        }
        .clipShape(.circle)
        .background(Circle().fill(.black))
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

struct FaceDetail: View {
    let id: String
    @Environment(Account.self) private var account
    @Environment(Catalog.self) private var catalog
    @Environment(WatchModel.self) private var watch
    @State private var face: Watchface?
    @State private var error: String?
    /// Install progress: 0…1, nil when idle.
    @State private var progress: Double?
    @State private var installed = false
    /// Images the install compressed to fit the watch's memory (fitForWatch).
    @State private var compressed = 0
    // DEBUG screenshots: `-install` opens the Nothing X warning
    @State private var closeNothingX = CommandLine.arguments.contains("-install")
    /// A file past the safe image size, waiting for the user's yes.
    @State private var big: (bin: Data, w: Int, h: Int)?
    @State private var failure: String?
    /// The watch is full: the install waits here for the dial to replace.
    @State private var slots: [DialSlot] = []
    @State private var picking: CheckedContinuation<UInt32?, Never>?
    /// Picked on the Install tap, before connecting.
    @State private var preset: UInt32?

    var body: some View {
        List {
            if let face {
                Section {
                    VStack(spacing: 14) {
                        FacePreview(url: face.previewURL).frame(maxWidth: 240)
                        VStack(spacing: 4) {
                            Text(face.name).font(.title2.bold()).multilineTextAlignment(.center)
                        }
                        if let other = face.otherDevice {
                            Label("Made for the \(other). It may not work on the Watch Pro 2.",
                                  systemImage: "exclamationmark.triangle.fill")
                                .font(.subheadline)
                                .foregroundStyle(.orange)
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(.orange.opacity(0.12), in: .rect(cornerRadius: 12))
                        }
                        install(face)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                } footer: {
                    Text(footer).frame(maxWidth: .infinity)
                }
                Section {
                    if let author = face.author, let owner = face.ownerId {
                        NavigationLink(value: "user:\(owner)") {
                            LabeledContent("Author") {
                                HStack(spacing: 8) {
                                    Avatar(url: face.authorAvatar, name: author, side: 24)
                                    Text(author)
                                }
                            }
                        }
                    }
                    LabeledContent("Downloads", value: "\(face.downloads)")
                    LabeledContent("Likes") { LikeButton(face: face) }
                }
                if !face.description.isEmpty {
                    Section("About") { Markdown(face.description).textSelection(.enabled) }
                }
            } else if let error {
                ContentUnavailableView("Couldn't load the watch face", systemImage: "exclamationmark.triangle",
                                       description: Text(error))
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let face {
                ToolbarItem(placement: .topBarTrailing) {
                    Link(destination: face.pageURL) { Label("Open on fmc.tools", systemImage: "safari") }
                }
            }
        }
        .alert("Close Nothing X first", isPresented: $closeNothingX) {
            Button("Install") { Task { await fetchAndInstall() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The watch talks to one app at a time. Close Nothing X (swipe it away in the app switcher), then install.\n\nAfterwards Nothing X may not see the watch. Then forget the watch in Bluetooth settings and add it again in Nothing X.")
        }
        .alert("Large images", isPresented: Binding { big != nil } set: { if !$0 { big = nil } }) {
            Button("Install anyway", role: .destructive) {
                if let bin = big?.bin { Task { await upload(bin) } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let big {
                Text("This face has a \(big.w)×\(big.h) px image, larger than factory faces use. Large images can make the watch restart in a loop.")
            }
        }
        // the faces as on the Watch tab: tap the one to replace. A swipe down cancels.
        .sheet(isPresented: Binding { picking != nil } set: { if !$0 { answer(nil) } }) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Pick the face to replace. Faces are in the order the watch shows them.")
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 20)
                    ScrollView(.horizontal) {
                        LazyHStack(alignment: .top, spacing: 12) {
                            ForEach(slots) { s in
                                Button { answer(s.id) } label: { DialTile(slot: s) }.buttonStyle(.plain)
                            }
                        }
                    }
                    .scrollIndicators(.visible)
                    .contentMargins(.horizontal, 20, for: .scrollContent)
                    .contentMargins(.bottom, 10, for: .scrollContent)
                    Spacer(minLength: 0)
                }
                .padding(.top, 8)
                .navigationTitle("The watch is full")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { answer(nil) } } }
            }
            .presentationDetents([.height(280)])
        }
        .alert("Couldn't install", isPresented: Binding { failure != nil } set: { if !$0 { failure = nil } }) {
            Button("OK") {}
        } message: {
            Text(failure ?? "")
        }
        .task(id: id) {
            do { face = try await catalog.face(id, account: account) } catch { self.error = error.localizedDescription }
        }
    }

    @ViewBuilder private func install(_ face: Watchface) -> some View {
        if let progress {
            ProgressView(value: progress) { Text("Installing…") }
                .padding(.horizontal, 20)
        } else {
            Button {
                Task { await start(face) }
            } label: {
                Label(installed ? "Installed" : "Install",
                      systemImage: installed ? "checkmark" : "arrow.down.circle")
            }
            .buttonStyle(.primary)
            .disabled(installed || face.binURL == nil)
            if installed, compressed > 0 {
                Text("\(compressed) images were compressed to fit the watch's memory. On the watch they can look slightly softer.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 20)
            }
        }
    }

    private var footer: LocalizedStringKey {
        "Close Nothing X before installing: the watch talks to one app at a time."
    }

    /// Full when last read: the dial to replace first, then the Nothing X warning.
    private func start(_ face: Watchface) async {
        preset = nil
        if let known = FaceInstall.knownFull(seed: face.id) {
            guard let picked = await pick(known) else { return }
            preset = picked
        }
        closeNothingX = true
    }

    private func pick(_ slots: [DialSlot]) async -> UInt32? {
        await withCheckedContinuation { c in
            self.slots = slots
            picking = c
        }
    }

    private func fetchAndInstall() async {
        guard let face else { return }
        progress = 0
        do {
            let (bin, verdict) = try await FaceInstall.fetch(face)
            if case .confirm(let w, let h) = verdict {
                progress = nil
                big = (bin, w, h)
                return
            }
            await upload(bin)
        } catch {
            progress = nil
            failure = error.localizedDescription
        }
    }

    private func upload(_ bin: Data) async {
        guard let face else { return }
        progress = 0
        defer { progress = nil }
        do {
            compressed = try await FaceInstall.install(bin, seed: face.id, name: face.name, slot: preset, pick: { await pick($0) }) { p in
                Task { @MainActor in progress = Double(p) / 100 }
            }
            UserDefaults.standard.set(face.thumbURL, forKey: "dial-preview-\(dialId(face.id))")
            installed = true
            Analytics.track("face_installed", ["id": face.id])
        } catch is CancellationError {
        } catch {
            failure = error.localizedDescription
        }
    }

    private func answer(_ id: UInt32?) {
        picking?.resume(returning: id)
        picking = nil
    }
}
