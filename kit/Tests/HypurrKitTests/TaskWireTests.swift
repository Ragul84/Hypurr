import Foundation
import Testing
@testable import HypurrKit

/// docs/reference/fixtures/task-wire.json: real `hypurr-host` output for a beginner task (shared with Android).
private let wireData: Data = {
    let url = URL(filePath: #filePath).deletingLastPathComponent()
        .appending(path: "../../../docs/reference/fixtures/task-wire.json").standardized
    return (try? Data(contentsOf: url)) ?? Data()
}()

private func decode<T: Decodable>(_ type: T.Type, _ key: String) throws -> T {
    let wire = try #require(try JSONSerialization.jsonObject(with: wireData) as? [String: Any])
    let data = try JSONSerialization.data(withJSONObject: try #require(wire[key]))
    return try JSONDecoder().decode(T.self, from: data)
}

@Test func taskBotDecodes() throws {
    let bot = try decode(Bot.self, "bot")
    let task = try #require(bot.task)
    #expect(task.branch?.hasPrefix("hypurr/write-tests-the-checkout-button-") == true)
    #expect(task.base == "main")
    #expect(task.hasSafetyNet)
    #expect(task.isActive)
    #expect(task.checkpoints?.first?.label == "Start (from main)")
}

@Test func explainedPermissionCardsDecode() throws {
    let pending = try decode(Entry.self, "pendingCard")
    #expect(pending.data.risk == "high")
    #expect(pending.data.explain == "The agent wants to delete build and everything inside.")
    #expect(pending.data.checkpoint != nil)
    #expect(pending.data.riskReasons?.isEmpty == false)
    let blocked = try decode(Entry.self, "blockedCard")
    #expect(blocked.data.blocked?.contains("main") == true)
    #expect(blocked.data.status == "answered")
}
