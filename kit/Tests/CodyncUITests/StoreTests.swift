import CryptoKit
import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

/// A scripted transport: tests set its link state and push events; it records what the store asked.
actor FakeRemote: RemoteTransport {
    private var state: LinkState
    private var stateSinks: [AsyncStream<LinkState>.Continuation] = []
    private var eventSinks: [AsyncThrowingStream<Data, Error>.Continuation] = []
    private var mailboxSinks: [AsyncStream<MailboxEvent>.Continuation] = []
    private(set) var sent: [String] = []
    private(set) var readReceipts: [Data] = []
    private var failReadReceipts = false
    private(set) var readAttempts = 0

    func setReadFailure(_ fail: Bool) { failReadReceipts = fail }
    private(set) var enqueued: [String] = []
    var cancelResult = MailboxCancel.cancelled

    init(_ state: LinkState) { self.state = state }

    var subscribed: Bool { !eventSinks.isEmpty }
    var eventSubscriptionCount: Int { eventSinks.count }

    func set(_ new: LinkState) {
        state = new
        for s in stateSinks { s.yield(new) }
    }

    func emit(_ json: String) {
        for s in eventSinks { s.yield(Data(json.utf8)) }
    }

    func emit(_ event: MailboxEvent) {
        for s in mailboxSinks { s.yield(event) }
    }

    private func respond(_ method: String, _ body: Data) throws -> Data {
        switch method {
        case "hello":
            return Data(#"{"hostId":"h1","name":"Mac","version":"3.0.0","os":"macos","backends":[],"rev":0}"#.utf8)
        case "markRead":
            readAttempts += 1
            if failReadReceipts { throw HostError.unreachable }
            readReceipts.append(body)
            return Data("{}".utf8)
        case "send":
            let b = (try JSONSerialization.jsonObject(with: body) as? [String: String]) ?? [:]
            sent.append(b["text"] ?? "")
            let entry = #"{"id":"e\#(sent.count)","seq":\#(sent.count),"botId":"\#(b["botId"] ?? "")","rev":\#(sent.count),"kind":"user","turn":1,"data":{"text":"\#(b["text"] ?? "")","clientNonce":"\#(b["clientNonce"] ?? "")"},"createdAt":1,"updatedAt":1}"#
            return Data(#"{"entry":\#(entry)}"#.utf8)
        default:
            return Data("{}".utf8)
        }
    }

    nonisolated func call(_ method: String, body: Data, timeout: TimeInterval) async throws -> Data {
        try await respond(method, body)
    }

    private func addEvents(_ c: AsyncThrowingStream<Data, Error>.Continuation) { eventSinks.append(c) }
    private func addState(_ c: AsyncStream<LinkState>.Continuation) {
        c.yield(state)
        stateSinks.append(c)
    }
    private func addMailbox(_ c: AsyncStream<MailboxEvent>.Continuation) { mailboxSinks.append(c) }

    nonisolated func stream(_ request: HostStreamRequest) -> AsyncThrowingStream<Data, Error> {
        AsyncThrowingStream { c in Task { await self.addEvents(c) } }
    }

    nonisolated func states() -> AsyncStream<LinkState> {
        AsyncStream { c in Task { await self.addState(c) } }
    }

    nonisolated func computerUpdates() -> AsyncStream<Computer> { AsyncStream { _ in } }

    func enqueue(botId: String, text: String, clientNonce: String, threadId: String?) async throws { enqueued.append(clientNonce) }
    func cancelQueued(clientNonce: String) async -> MailboxCancel { cancelResult }
    func listQueued() async throws -> [QueuedItem] { enqueued.map { QueuedItem(nonce: $0, exp: nil, state: "queued") } }

    nonisolated func mailboxEvents() -> AsyncStream<MailboxEvent> {
        AsyncStream { c in Task { await self.addMailbox(c) } }
    }

    func shutdown() async {
        for s in eventSinks { s.finish() }
        for s in stateSinks { s.finish() }
        eventSinks = []
        stateSinks = []
    }
}

private func randomComputer(_ name: String) -> Computer {
    let sk = Curve25519.Signing.PrivateKey().publicKey.rawRepresentation
    return Computer(id: RelayCrypto.computerId(signKey: sk), name: name, signKey: sk.base64URLForTests,
                    boxKey: Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation.base64URLForTests,
                    cloud: URL(string: "https://cloud.example.dev"))
}

private extension Data {
    var base64URLForTests: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

private func context() -> (SharedStore.Context, String) {
    let suite = "CodyncUITests.\(UUID().uuidString)"
    return (SharedStore.Context(accountID: "user_\(UUID().uuidString)", suite: suite), suite)
}

/// Polls until `condition` holds (2 s at most).
@MainActor
private func until(_ condition: @MainActor () async -> Bool) async -> Bool {
    for _ in 0..<200 {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return false
}

private func botEvent(_ id: String, name: String, rev: Int) -> String {
    #"{"type":"bot","bot":{"id":"\#(id)","name":"\#(name)","rev":\#(rev),"lastAt":\#(rev)}}"#
}

@MainActor @Test func offlineSendsWaitInTheMailbox() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.hostOffline(lastSeen: nil))
    let store = BotStore(computer: randomComputer("Mac"), route: .channel, clientKind: "ios", storage: storage) { fake }
    store.setActive(true)
    #expect(await until { store.connection == .computerOffline(lastSeen: nil) })
    #expect(store.canQueue && store.isOffline)

    store.send("hi", to: "b1")
    #expect(await until { await fake.enqueued.count == 1 })
    let waiting = try #require(store.chat("b1").first)
    #expect(waiting.data.status == "waiting")
    #expect(await fake.sent.isEmpty)

    // Cancelled before the computer saw it: gone.
    store.cancelQueued(waiting)
    #expect(await until { store.chat("b1").isEmpty })

    // Already handed over: it stays, marked delivered.
    store.send("second", to: "b1")
    #expect(await until { await fake.enqueued.count == 2 })
    await fake.setCancelResult(.delivering)
    let second = try #require(store.chat("b1").first)
    store.cancelQueued(second)
    #expect(await until { store.chat("b1").first?.data.status == "delivering" })

    // Expired in the mailbox: failed, can be resent.
    store.send("third", to: "b2")
    #expect(await until { await fake.enqueued.count == 3 })
    let third = try #require(store.chat("b2").first?.data.clientNonce)
    await fake.emit(.expired(nonce: third))
    #expect(await until { store.chat("b2").first?.data.status == "failed" })

    // Back online: sends go straight to the computer, and its copy replaces the local one.
    await fake.set(.ready(.relay))
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Rex", rev: 1))
    #expect(await until { store.connection == .online })
    store.send("live", to: "b3")
    #expect(await until { store.chat("b3").first?.id == "e1" })
    #expect(await fake.sent == ["live"])
    store.retire()
}

@MainActor @Test func accountKeepsComputersApart() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let c1 = randomComputer("Mac")
    let c2 = randomComputer("Linux box")
    storage.computers = [c1, c2]
    let fakes = [c1.id: FakeRemote(.ready(.direct)), c2.id: FakeRemote(.ready(.relay))]
    let account = AccountStore(storage: storage, clientKind: "ios", cloud: nil) { computer, route in
        BotStore(computer: computer, route: route, clientKind: "ios", storage: storage) { fakes[computer.id]! }
    }
    var updates: [BotReference] = []
    account.onBotUpdated = { ref, _ in updates.append(ref) }
    #expect(account.computers.map(\.id) == [c1.id, c2.id])
    for fake in fakes.values { #expect(await until { await fake.subscribed }) }

    // Same bot ID on both computers.
    await fakes[c1.id]!.emit(botEvent("b1", name: "Mac bot", rev: 1))
    await fakes[c2.id]!.emit(botEvent("b1", name: "Linux bot", rev: 2))
    #expect(await until { account.roster.count == 2 })
    let refs = account.roster.map(\.ref)
    #expect(Set(refs).count == 2)
    #expect(Set(refs.map(\.computerId)) == [c1.id, c2.id])
    #expect(account.roster.first { $0.ref.computerId == c2.id }?.bot.name == "Linux bot")
    #expect(Set(updates) == Set(refs))

    // Routing by reference reaches only that computer.
    let ref = try #require(refs.first { $0.computerId == c2.id })
    account.store(for: ref.computerId)?.send("hello", to: ref.botId)
    #expect(await until { await fakes[c2.id]!.sent == ["hello"] })
    #expect(await fakes[c1.id]!.sent.isEmpty)
    #expect(storage.lastComputerId == c2.id)

    // After switching accounts, late events write nothing.
    let old = account.store(for: c1.id)
    account.retire()
    await fakes[c1.id]!.emit(#"{"type":"usage","usage":{"providers":[{"id":"claude","name":"Claude","windows":[],"source":"x","updatedAt":1}]}}"#)
    try? await Task.sleep(for: .milliseconds(100))
    #expect(storage.usage[c1.id] == nil)
    #expect(old?.usage.providers.isEmpty == true)
    #expect(updates.count == 2)
}

@MainActor @Test func computersReorderAndStaySaved() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let a = randomComputer("A"), b = randomComputer("B"), c = randomComputer("C")
    storage.computers = [a, b, c]
    let account = AccountStore(storage: storage, clientKind: "ios", cloud: nil) { computer, route in
        BotStore(computer: computer, route: route, clientKind: "ios", storage: storage) { FakeRemote(.ready(.direct)) }
    }
    account.move(a.id, to: c.id)
    #expect(account.computers.map(\.id) == [b.id, c.id, a.id])
    account.move(a.id, to: b.id)
    #expect(account.computers.map(\.id) == [a.id, b.id, c.id])
    account.move(c.id, to: b.id)
    #expect(storage.computers.map(\.id) == [a.id, c.id, b.id])
    account.retire()
}

@MainActor @Test func attachedComputersAreNotSaved() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let saved = randomComputer("Saved")
    storage.computers = [saved]
    let fake = FakeRemote(.ready(.loopback))
    let account = AccountStore(storage: storage, clientKind: "mac", cloud: nil) { _, _ in
        BotStore(computer: saved, route: .channel, clientKind: "mac", storage: storage) { fake }
    }
    let local = randomComputer("This Mac")
    account.attach(local, route: .loopback(baseURL: URL(string: "http://127.0.0.1:19222")!, token: "t"))
    #expect(account.computers.map(\.id) == [local.id, saved.id])
    #expect(storage.computers.map(\.id) == [saved.id])
    account.setColor(saved.id, "red")
    #expect(storage.computers.first?.color == "red")
    account.detach(local.id)
    #expect(account.computers.map(\.id) == [saved.id])
    account.forget(saved.id)
    #expect(storage.computers.isEmpty && account.stores.isEmpty)
    account.retire()
}

extension FakeRemote {
    func setCancelResult(_ result: MailboxCancel) { cancelResult = result }
}


@MainActor @Test func visibleConversationAcknowledgesEntriesBeforeUnreadAndFinalUpdates() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), route: .channel, clientKind: "ios", storage: storage) { fake }
    store.setActive(true)
    defer { store.setActive(false) }
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.bots["b1"] != nil })
    let token = UUID()
    store.setReading(token, botId: "b1", thread: nil, active: true)
    #expect(await until { await fake.readReceipts.count == 1 })
    // Streaming and final content have the same ID. The final update must be read
    // even though the roster has not yet reported any unread messages.
    await fake.emit(#"{"type":"entry","entry":{"id":"answer","seq":1,"botId":"b1","rev":2,"kind":"agent","turn":1,"data":{"text":"draft","final":false},"createdAt":1,"updatedAt":1}}"#)
    #expect(await until { store.allEntries("b1").last?.rev == 2 })
    await fake.emit(#"{"type":"entry","entry":{"id":"answer","seq":1,"botId":"b1","rev":3,"kind":"agent","turn":1,"data":{"text":"done","final":true},"createdAt":1,"updatedAt":2}}"#)
    #expect(await until { await fake.readReceipts.count == 2 })
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Bot","rev":4,"unread":1}}"#)
    #expect(await until { await fake.readReceipts.count == 3 })
    store.setReading(token, botId: "b1", thread: nil, active: false)
    await fake.emit(#"{"type":"bot","bot":{"id":"b1","name":"Bot","rev":5,"unread":2}}"#)
    #expect(await until { store.bots["b1"]?.rev == 5 })
    try await Task.sleep(for: .milliseconds(50))
    #expect(await fake.readReceipts.count == 3)
    // Returning to the foreground acknowledges without needing navigation.
    store.setReading(token, botId: "b1", thread: nil, active: true)
    #expect(await until { await fake.readReceipts.count == 4 })
}

@MainActor @Test func readingOneThreadDoesNotAcknowledgeOtherThreads() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), route: .channel, clientKind: "mac", storage: storage) { fake }
    store.setActive(true)
    defer { store.setActive(false) }
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })
    let token = UUID()
    store.setReading(token, botId: "b1", thread: "root-a", active: true)
    #expect(await until { await fake.readReceipts.count == 1 })
    await fake.emit(#"{"type":"entry","entry":{"id":"other","seq":1,"botId":"b1","threadId":"root-b","rev":2,"kind":"agent","turn":1,"data":{"text":"done","final":true},"createdAt":1,"updatedAt":1}}"#)
    #expect(await until { store.allEntries("b1").last?.id == "other" })
    try await Task.sleep(for: .milliseconds(50))
    #expect(await fake.readReceipts.count == 1)
    await fake.emit(#"{"type":"entry","entry":{"id":"own","seq":2,"botId":"b1","threadId":"root-a","rev":3,"kind":"agent","turn":1,"data":{"text":"done","final":true},"createdAt":1,"updatedAt":1}}"#)
    #expect(await until { await fake.readReceipts.count == 2 })
    for receipt in await fake.readReceipts {
        let body = try #require(JSONSerialization.jsonObject(with: receipt) as? [String: Any])
        #expect(body["threadId"] as? String == "root-a")
        #expect(body["all"] as? Bool == false)
    }
}

@MainActor @Test func initialOfflineReportWaitsForReconnect() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.hostOffline(lastSeen: nil))
    let store = BotStore(computer: randomComputer("Mac"), route: .channel, clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    try await Task.sleep(for: .milliseconds(200))
    #expect(store.connection == .connecting)
    #expect(store.lastError == nil)
    await fake.set(.ready(.relay))
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })
    try await Task.sleep(for: .seconds(1))
    #expect(store.connection == .online)
}

@MainActor @Test func initialUnreachableEventuallyShowsOffline() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.failed("Can't reach"))
    let store = BotStore(computer: randomComputer("Mac"), route: .channel, clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    try await Task.sleep(for: .milliseconds(200))
    #expect(store.connection == .connecting)
    #expect(await until { store.connection == .offline("Can't reach") })
    #expect(store.lastError == nil)
}

@MainActor @Test func failedAutomaticReadDoesNotInterruptChatAndRecoversWithTheLink() async throws {
    let (storage, suite) = context()
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    let fake = FakeRemote(.ready(.direct))
    let store = BotStore(computer: randomComputer("Mac"), route: .channel, clientKind: "ios", storage: storage) { fake }
    defer { store.retire() }
    store.setActive(true)
    #expect(await until { await fake.subscribed })
    await fake.emit(botEvent("b1", name: "Bot", rev: 1))
    #expect(await until { store.connection == .online })
    await fake.setReadFailure(true)
    store.setReading(UUID(), botId: "b1", thread: nil, active: true)
    #expect(await until { await fake.readAttempts == 1 })
    try await Task.sleep(for: .milliseconds(50))
    #expect(store.lastError == nil)
    await fake.set(.hostOffline(lastSeen: nil))
    #expect(await until { store.hostRoute == nil })
    #expect(store.connection == .online)
    #expect(store.canQueue)
    await fake.setReadFailure(false)
    await fake.set(.ready(.relay))
    #expect(await until { await fake.eventSubscriptionCount == 2 })
    await fake.emit(botEvent("b2", name: "Other bot", rev: 2))
    #expect(await until { await fake.readReceipts.count == 1 })
    #expect(store.connection == .online)
    #expect(store.lastError == nil)
}
