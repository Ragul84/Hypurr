import AppKit
import CodyncKit
import CodyncUI
import CryptoKit
import Foundation
import Observation
import os
import ServiceManagement

/// A device asking a computer this Mac manages (itself or over SSH) for access.
@MainActor
struct Approval: Identifiable {
    let store: BotStore
    let request: AccessRequest
    let id: String

    init(store: BotStore, request: AccessRequest) {
        self.store = store
        self.request = request
        id = "\(store.computer.id)/\(request.requestId)"
    }
}

private let log = Logger(subsystem: "com.pokai.Codync", category: "Host")

/// Manages the local codync-host (binary, background service) and owns the `AccountStore`
/// the menu and the chat window share: this Mac over loopback, SSH computers through their
/// tunnels, and the account's other computers over the encrypted channel.
@MainActor
@Observable
final class HostController {
    enum State: Equatable {
        case missingBinary
        case notInstalled
        case starting
        case running
        case failed(String)
    }

    /// `CODYNC_PORT` / `CODYNC_HOME` point the app at a dev host started with `codync-host serve`.
    static let devPort = ProcessInfo.processInfo.environment["CODYNC_PORT"].flatMap(Int.init)
    static let port = devPort ?? 19222
    static let dataDir = ProcessInfo.processInfo.environment["CODYNC_HOME"].map { URL(filePath: $0) }
        ?? FileManager.default.homeDirectoryForCurrentUser.appending(path: ".codync")
    static let baseURL = URL(string: "http://127.0.0.1:\(port)")!

    let account: AccountSession
    let ssh = SSHComputers()
    /// One per account context; switching accounts retires it.
    private(set) var accounts: AccountStore
    /// The signed-in account's cloud; nil when signed out or the build has no cloud.
    private(set) var cloud: CloudClient?
    private(set) var contextID: String
    /// This Mac's own host, once it answered.
    private(set) var local: Computer?
    private var localToken: String?

    private(set) var state: State = .starting
    var launchAtLogin: Bool = SMAppService.mainApp.status == .enabled
    /// Codync Screen (capture + input for Remote screen), a launchd agent inside this app.
    private let screenAgent = SMAppService.agent(plistName: "com.pokai.Codync.screen.plist")
    private(set) var screenAgentNeedsApproval = false
    private(set) var screenError: String?
    /// Approvals closed with "later"; they come back when the request changes (e.g. its code arrives).
    private var deferred: Set<String> = []

    private var streamTask: Task<Void, Never>?
    /// Set once the service was moved onto this app's host because it ran another version.
    private var replacedStaleHost = false
    private var expectedBinaryHash: String?
    private var installingHost = false
    private var preparingForUpdate = false

    init(account: AccountSession) {
        #if DEBUG
        SSH.selfCheck()
        #endif
        self.account = account
        let storage = SharedStore.Context(accountID: account.userID)
        SharedStore.activeAccountID = account.userID
        contextID = storage.id
        (accounts, cloud) = Self.makeAccounts(storage, session: account)
        ssh.onAttach = { [weak self] attachment in
            self?.accounts.attach(attachment.computer, route: .loopback(baseURL: attachment.baseURL, token: attachment.token))
        }
        ssh.onDetach = { [weak self] id in self?.accounts.detach(id) }
        // ssh children outlive the app unless stopped.
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ssh.disconnectAll() }
        }
    }

    /// The Mac's own host store.
    var store: BotStore? { local.flatMap { accounts.store(for: $0.id) } }

    /// Computers this Mac manages over loopback (itself and SSH tunnels): they take approvals, pairing and claims.
    var managedStores: [BotStore] {
        accounts.computers.compactMap { accounts.store(for: $0.id) }.filter(\.isLoopback)
    }

    var approvals: [Approval] {
        managedStores.flatMap { store in store.accessRequests.map { Approval(store: store, request: $0) } }
    }

    /// The approval to show now: the first one not put off, or put off before its code arrived.
    var currentApproval: Approval? {
        approvals.first { !deferred.contains(deferKey($0)) }
    }

    func deferApproval(_ approval: Approval) { deferred.insert(deferKey(approval)) }

    /// Brings every waiting request back (the menu's Review).
    func reviewApprovals() { deferred = [] }

    private func deferKey(_ a: Approval) -> String { "\(a.id)/\(a.request.code ?? "")" }

    var roster: [RosterItem] { accounts.roster }
    var usage: Usage { store?.usage ?? Usage() }
    var screen: ScreenState? { store?.screen }
    var version: String? { store?.hello?.version }
    var needsAttention: Bool { roster.contains(where: \.bot.needsInput) || !approvals.isEmpty }
    var working: Int { roster.filter(\.bot.isWorking).count }

    func isSSH(_ id: ComputerID) -> Bool { ssh.attachments.values.contains { $0.computer.id == id } }

    /// Bundled next to the app executable, else a Homebrew / cargo install.
    var binaryURL: URL? {
        let candidates = [
            Bundle.main.bundleURL.appending(path: "Contents/MacOS/codync-host"),
            URL(filePath: "/opt/homebrew/bin/codync-host"),
            URL(filePath: "/usr/local/bin/codync-host"),
            FileManager.default.homeDirectoryForCurrentUser.appending(path: ".cargo/bin/codync-host"),
        ]
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0.path) }
    }

    private var plistURL: URL {
        FileManager.default.homeDirectoryForCurrentUser.appending(path: "Library/LaunchAgents/com.pokai.codync.host.plist")
    }

    private var tokenURL: URL { Self.dataDir.appending(path: "token") }

    var logURL: URL { Self.dataDir.appending(path: "host.log") }

    /// The menu's images are drawn for one appearance; this follows the system's so they're redrawn.
    private(set) var menuIsDark = false
    @ObservationIgnored private var appearanceObservation: NSKeyValueObservation?
    /// Bumped by "Pair iPhone…": the pairing window stays open between uses, so it fetches a fresh code.
    private(set) var pairingRequest = 0

    func requestPairing() { pairingRequest += 1 }

    func start() {
        menuIsDark = NSApp.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
        appearanceObservation = NSApp.observe(\.effectiveAppearance) { [weak self] app, _ in
            let dark = app.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            Task { @MainActor in self?.menuIsDark = dark }
        }
        refresh()
        ssh.connectAll()
        watchAutoClaim()
        watchCloudDefault()
    }

    /// Cloudflare is on by default, so a Mac without Tailscale is reachable away from home. It's turned on
    /// once per host identity, the first time it's seen off; after that, off stays off, whoever turned it
    /// off (this app's switch, `codync-host cloud --disable`), and it's never undone behind the user's back.
    private func watchCloudDefault() {
        let target = withObservationTracking {
            cloudDefaultTarget
        } onChange: { [weak self] in
            Task { @MainActor in self?.watchCloudDefault() }
        }
        guard let target, let client = target.client, !enablingCloud else { return }
        enablingCloud = true
        Self.markCloudDefaultApplied(target.computer.id)
        Task {
            do {
                _ = try await enableCloud(client, store: target)
            } catch {
                log.error("turning Cloudflare on by default failed: \(error.localizedDescription, privacy: .public)")
            }
            enablingCloud = false
        }
    }

    private var enablingCloud = false
    /// Host identities whose Cloudflare default has been applied (or found already on).
    private static let cloudDefaultKey = "cloudDefaultApplied"

    private static func cloudDefaultApplied(_ id: ComputerID) -> Bool {
        UserDefaults.standard.stringArray(forKey: cloudDefaultKey)?.contains(id) ?? false
    }

    private static func markCloudDefaultApplied(_ id: ComputerID) {
        let ids = UserDefaults.standard.stringArray(forKey: cloudDefaultKey) ?? []
        if !ids.contains(id) { UserDefaults.standard.set(ids + [id], forKey: cloudDefaultKey) }
    }

    private var cloudDefaultTarget: BotStore? {
        guard account.cloudURL != nil, let store, store.connection == .online, let status = store.cloud,
              !Self.cloudDefaultApplied(store.computer.id) else { return nil }
        // Already on: nothing to apply, and a later "off" is the user's.
        if status.enabled {
            Self.markCloudDefaultApplied(store.computer.id)
            return nil
        }
        return store
    }

    func refresh() {
        guard !preparingForUpdate else { return }
        guard binaryURL != nil else {
            state = .missingBinary
            return
        }
        if Self.devPort == nil, UserDefaults.standard.bool(forKey: Self.updateRestartKey),
           FileManager.default.fileExists(atPath: plistURL.path) {
            install()
            return
        }
        guard Self.devPort != nil || FileManager.default.fileExists(atPath: plistURL.path) else {
            // The app is the way to run the host on a Mac: set it up right away, unless the user took it out.
            if UserDefaults.standard.bool(forKey: Self.uninstalledKey) { state = .notInstalled } else { install() }
            return
        }
        connect()
    }

    /// Set by "Uninstall host service", so the next launch doesn't put the host back.
    private static let uninstalledKey = "hostUninstalled"
    private static let updateRestartKey = "hostRestartAfterAppUpdate"

    /// `codync-host install` also routes Claude Code's status line through the host.
    func install() {
        guard !preparingForUpdate, !installingHost, let bin = binaryURL else { return }
        installingHost = true
        streamTask?.cancel()
        UserDefaults.standard.set(false, forKey: Self.uninstalledKey)
        state = .starting
        Task {
            defer { installingHost = false }
            let result = await Self.run(bin, ["install", "--port", "\(Self.port)"])
            if result.status != 0 {
                state = .failed(result.output.isEmpty ? "Install failed" : result.output)
            } else {
                try? await Task.sleep(for: .seconds(1))
                connect()
            }
        }
    }

    func restart() {
        guard Self.devPort == nil else {
            state = .failed("Restart the manually started development host in its terminal.")
            return
        }
        // Reinstall also updates the executable path and waits for the old host
        // to release its data lock. A kickstart could restart another app's copy.
        replacedStaleHost = false
        install()
    }

    func uninstall() {
        guard let bin = binaryURL else { return }
        streamTask?.cancel()
        UserDefaults.standard.set(true, forKey: Self.uninstalledKey)
        Task {
            _ = await Self.run(bin, ["uninstall"])
            if let local { accounts.detach(local.id) }
            local = nil
            localToken = nil
            state = .notInstalled
        }
    }

    func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {}
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    /// Remote screen: starts/stops Codync Screen and tells the host (which only accepts this from the Mac itself).
    func setRemoteScreen(_ on: Bool) {
        guard !preparingForUpdate else { return }
        Task {
            do {
                if on { try screenAgent.register() } else { try await screenAgent.unregister() }
                UserDefaults.standard.set(on ? Self.helperStamp : nil, forKey: Self.helperStampKey)
                try await store?.setScreenEnabled(on)
                screenError = nil
            } catch {
                screenError = error.localizedDescription
            }
            screenAgentNeedsApproval = screenAgent.status == .requiresApproval
        }
    }

    func openLoginItemsSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }

    /// Keeps Codync Screen registered while the host has Remote screen on (e.g. after the app moved),
    /// and registers it again when this app ships a different helper binary (an update or a rebuild):
    /// launchd keeps running the old helper, and a new binary under the old registration fails to start.
    private func syncScreenAgent() async {
        guard !preparingForUpdate else { return }
        guard screen?.enabled == true else {
            screenAgentNeedsApproval = false
            return
        }
        let stamp = Self.helperStamp
        let stale = UserDefaults.standard.string(forKey: Self.helperStampKey) != stamp
        guard stale || screenAgent.status != .enabled else {
            screenAgentNeedsApproval = false
            return
        }
        if stale, screenAgent.status == .enabled {
            log.info("Codync Screen changed; registering it again")
            // Stops the old helper along with its job.
            try? await screenAgent.unregister()
        }
        try? screenAgent.register()
        if screenAgent.status == .enabled { UserDefaults.standard.set(stamp, forKey: Self.helperStampKey) }
        screenAgentNeedsApproval = screenAgent.status == .requiresApproval
    }

    private static let helperStampKey = "screenHelperStamp"

    /// Identifies the helper binary inside this app: its path and modification date.
    private static var helperStamp: String {
        let url = Bundle.main.bundleURL.appending(path: "Contents/Library/LoginItems/CodyncScreen.app/Contents/MacOS/CodyncScreen")
        let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
        return "\(url.path)@\(modified?.timeIntervalSince1970 ?? 0)"
    }

    func isIdleForUpdate() async -> Bool {
        guard !installingHost, !preparingForUpdate, Self.devPort == nil else { return false }
        if !FileManager.default.fileExists(atPath: plistURL.path) { return true }
        guard let health = await HostHealth.fetch(Self.baseURL) else { return false }
        return health.busy == false
    }

    func prepareForUpdate() async throws {
        guard Self.devPort == nil else { return }
        guard !installingHost else {
            throw NSError(domain: "Codync.Update", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "The host is being installed. Try again when it finishes."])
        }
        preparingForUpdate = true
        // Persist before stopping: Sparkle can install on Quit and relaunch later.
        if FileManager.default.fileExists(atPath: plistURL.path) {
            UserDefaults.standard.set(true, forKey: Self.updateRestartKey)
        }
        streamTask?.cancel()
        if screenAgent.status == .enabled {
            try await screenAgent.unregister()
            UserDefaults.standard.removeObject(forKey: Self.helperStampKey)
        }
        guard let bin = binaryURL else {
            throw NSError(domain: "Codync.Update", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "The bundled host is missing."])
        }
        let result = await Self.run(bin, ["stop"])
        guard result.status == 0 else {
            throw NSError(domain: "Codync.Update", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: result.output.isEmpty ? "The old host could not be stopped." : result.output])
        }
    }

    func resumeAfterCancelledUpdate() {
        guard preparingForUpdate else { return }
        preparingForUpdate = false
        if FileManager.default.fileExists(atPath: plistURL.path) { install() } else { refresh() }
    }

    func stop(_ item: RosterItem) {
        accounts.store(for: item.ref.computerId)?.stop(item.bot.id)
    }

    // MARK: account

    func switchAccount(to userID: String?) {
        let storage = SharedStore.Context(accountID: userID)
        guard storage.id != contextID else { return }
        accounts.retire()
        SharedStore.activeAccountID = userID
        contextID = storage.id
        deferred = []
        (accounts, cloud) = Self.makeAccounts(storage, session: account)
        // This Mac and the SSH tunnels belong to the Mac, not to an account: they follow into the new context.
        if let local, let localToken { accounts.attach(local, route: .loopback(baseURL: Self.baseURL, token: localToken)) }
        for attachment in ssh.attachments.values {
            accounts.attach(attachment.computer, route: .loopback(baseURL: attachment.baseURL, token: attachment.token))
        }
    }

    /// Signing out forgets the account on this Mac: its device keys, computers and caches (spec §3.2).
    func signOut() async {
        guard let userID = account.userID else { return }
        await account.signOut()
        guard account.userID != userID, !account.accounts.contains(where: { $0.id == userID }) else { return }
        switchAccount(to: account.userID)
        SharedStore.Context(accountID: userID).erase()
    }

    /// §4.2 A: makes a computer this Mac manages part of the signed-in account. Its relay is turned on first,
    /// since joining the account is about reaching it from anywhere.
    /// `quiet`: the automatic join, which logs a failure and retries instead of showing it.
    @discardableResult
    func claim(_ store: BotStore, quiet: Bool = false) async -> Bool {
        guard let cloud, let userID = account.userID, let client = store.client else {
            if !quiet { accounts.lastError = "Sign in first." }
            return false
        }
        Self.setKeptOut(store.computer.id, false, userID: userID)
        do {
            if store.cloud?.enabled != true { _ = try await enableCloud(client, store: store) }
            let challenge = try await cloud.createClaim()
            let signed = try await client.claimSign(claimId: challenge.claimId, nonce: challenge.nonce, userId: userID)
            _ = try await cloud.completeClaim(challenge.claimId, signed: signed)
            await accounts.refreshCloud()
            if store.computer.id == self.store?.computer.id { await forgetEarlierIdentities(of: store.computer.id) }
            return true
        } catch {
            if quiet {
                log.error("joining the account failed: \(error.localizedDescription, privacy: .public)")
            } else {
                accounts.lastError = error.localizedDescription
            }
            return false
        }
    }

    /// Signed in, this Mac joins the account on its own, unless the user took it out of that account.
    private func watchAutoClaim() {
        let pending = withObservationTracking {
            autoClaimTarget
        } onChange: { [weak self] in
            Task { @MainActor in self?.watchAutoClaim() }
        }
        // Remember which identity is this Mac while it's in the account, so a later key reset can clean it up.
        if let store, let userID = account.userID, store.cloud?.owner?.userId == userID {
            UserDefaults.standard.set([store.computer.id], forKey: "claimedComputerIds")
        }
        guard let pending, !autoClaiming else { return }
        autoClaiming = true
        Task {
            // A failed join (network hiccup) is retried with backoff for as long as it's still wanted.
            var delay = 30.0
            while await !claim(pending, quiet: true) {
                try? await Task.sleep(for: .seconds(delay))
                delay = min(delay * 2, 600)
                guard autoClaimTarget?.computer.id == pending.computer.id else { break }
            }
            autoClaiming = false
        }
    }

    private var autoClaiming = false

    /// This Mac's host, online, in no account yet, for a signed-in user who hasn't removed it.
    private var autoClaimTarget: BotStore? {
        // Only with Cloudflare on: joining needs it, and turning it back on is never done silently.
        guard let userID = account.userID, let store, store.connection == .online,
              let status = store.cloud, status.enabled, status.owner == nil,
              !Self.keptOut(userID).contains(store.computer.id) else { return nil }
        return store
    }

    /// Computers the user removed from an account on this Mac; auto-join leaves them out.
    private static func keptOut(_ userID: String) -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: "keptOutOfAccount.\(userID)") ?? [])
    }

    private static func setKeptOut(_ id: String, _ out: Bool, userID: String) {
        var ids = keptOut(userID)
        if out { ids.insert(id) } else { ids.remove(id) }
        UserDefaults.standard.set(Array(ids), forKey: "keptOutOfAccount.\(userID)")
    }

    /// This Mac's host got new keys (a reset `~/.codync`, a reinstall): the identities this Mac claimed
    /// before are dead copies of it, so they leave the account instead of showing up as a second Mac.
    private func forgetEarlierIdentities(of current: ComputerID) async {
        let key = "claimedComputerIds"
        let earlier = Set(UserDefaults.standard.stringArray(forKey: key) ?? []).subtracting([current])
        for computer in accounts.cloudComputers where earlier.contains(computer.computerId) && !computer.isOnline {
            await accounts.removeFromAccount(computer.computerId)
        }
        UserDefaults.standard.set([current], forKey: key)
    }

    func unclaim(_ store: BotStore) async {
        if let userID = account.userID { Self.setKeptOut(store.computer.id, true, userID: userID) }
        do {
            try await store.client?.unclaim()
            await accounts.refreshCloud()
        } catch {
            accounts.lastError = error.localizedDescription
        }
    }

    func setCloud(_ store: BotStore, enabled: Bool) async {
        guard let client = store.client else { return }
        Self.markCloudDefaultApplied(store.computer.id)
        do {
            _ = enabled ? try await enableCloud(client, store: store) : try await client.setCloud(enabled: false)
        } catch {
            accounts.lastError = error.localizedDescription
        }
    }

    /// A host without a cloud URL of its own gets this app's.
    private func enableCloud(_ client: HostClient, store: BotStore) async throws -> CloudStatus {
        let url = store.cloud?.url == nil ? account.cloudURL : nil
        guard store.cloud?.url != nil || url != nil else {
            throw CloudError(status: 400, code: "badRequest", message: "This build of Codync has no cloud to connect to.")
        }
        return try await client.setCloud(enabled: true, url: url)
    }

    func decide(_ approval: Approval, approve: Bool) async throws {
        guard let client = approval.store.client else { throw HostError.unreachable }
        try await client.decideAccessRequest(approval.request.requestId, approve: approve)
    }

    private static func makeAccounts(_ storage: SharedStore.Context, session: AccountSession) -> (AccountStore, CloudClient?) {
        let identity = storage.accountID == nil ? nil : try? DeviceIdentity.load(context: storage)
        let cloud = session.cloudClient(for: storage.accountID, identity: identity)
        let accounts = AccountStore(storage: storage, clientKind: "mac", cloud: cloud)
        if let cloud {
            // Signed in: make this Mac known to the account, then list its computers.
            Task { [weak accounts] in
                do {
                    try await cloud.registerDevice(name: Host.current().localizedName ?? "Mac", platform: "macos")
                } catch let error as CloudError where !error.isTransient {
                    accounts?.lastError = error.localizedDescription
                } catch {
                    // Transient (network, busy cloud, token not ready): the next launch registers again.
                }
                await accounts?.refreshCloud()
            }
        }
        return (accounts, cloud)
    }

    // MARK: local host

    private func readToken() -> String? {
        (try? String(contentsOf: tokenURL, encoding: .utf8)).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    }

    /// Watches the host's health; the BotStore handles the event stream itself.
    private func connect() {
        guard !preparingForUpdate else { return }
        streamTask?.cancel()
        streamTask = Task { [weak self] in
            if let bin = self?.binaryURL {
                let hash = await Task.detached {
                    (try? Data(contentsOf: bin)).map { data in
                        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                    }
                }.value
                guard !Task.isCancelled else { return }
                self?.expectedBinaryHash = hash
            }
            var failures = 0
            while !Task.isCancelled {
                guard let self else { return }
                if await self.attachLocal() {
                    failures = 0
                    self.state = .running
                    if Self.devPort == nil {
                        UserDefaults.standard.removeObject(forKey: Self.updateRestartKey)
                    }
                    await self.syncScreenAgent()
                } else {
                    failures += 1
                    if failures > 5 { self.state = .failed("The host isn't responding. See the log for details.") }
                    else if self.state != .running { self.state = .starting }
                }
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    /// Attaches the local host once it answers, and again when its token or identity changed.
    private func attachLocal() async -> Bool {
        guard let token = readToken(), let health = await HostHealth.fetch(Self.baseURL), let id = health.computerId else {
            return false
        }
        // A rebuild can change the host without changing the release version.
        // Legacy hosts have no fingerprint and are replaced once as well.
        if Self.devPort == nil {
            guard let expectedBinaryHash, let bin = binaryURL else { return false }
            let matches = health.binaryHash == expectedBinaryHash
                && health.binaryPath == bin.resolvingSymlinksInPath().path
            if !matches {
                if !replacedStaleHost {
                    log.info("Host binary differs from this app's; reinstalling the service")
                    replacedStaleHost = true
                    install()
                }
                return false
            }
        }
        if Task.isCancelled {
            return false
        }
        if local?.id == id, localToken == token, store != nil { return true }
        guard let hello = try? await HostClient(baseURL: Self.baseURL, token: token).hello(),
              hello.computerId == id, let signKey = hello.signKey else { return false }
        let computer = Computer(id: id, name: hello.name, signKey: signKey, boxKey: hello.boxKey, urls: hello.urls ?? [],
                                cloud: hello.cloud.flatMap(URL.init(string:)), device: hello.device,
                                color: store?.computer.color)
        if let old = local, old.id != id { accounts.detach(old.id) }
        local = computer
        localToken = token
        accounts.attach(computer, route: .loopback(baseURL: Self.baseURL, token: token))
        return true
    }

    nonisolated static func run(_ bin: URL, _ args: [String]) async -> (status: Int32, output: String) {
        // The host rebuilds PATH from the login shell itself (backends::hydrate_path).
        let result = await ProcessRunner.run(bin, args)
        return (result.status, (result.text + result.stderr).trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

extension BotStore {
    var isLoopback: Bool {
        if case .loopback = route { true } else { false }
    }
}
