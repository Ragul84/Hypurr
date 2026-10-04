import Foundation
import Testing
@testable import HypurrKit

/// docs/reference/fixtures/work-wire.json: real `hypurr-host` output for a finished workplace task.
private let workWireData: Data = {
    let url = URL(filePath: #filePath).deletingLastPathComponent()
        .appending(path: "../../../docs/reference/fixtures/work-wire.json").standardized
    return (try? Data(contentsOf: url)) ?? Data()
}()

private func decodeWork<T: Decodable>(_ type: T.Type, _ key: String) throws -> T {
    let wire = try #require(try JSONSerialization.jsonObject(with: workWireData) as? [String: Any])
    let data = try JSONSerialization.data(withJSONObject: try #require(wire[key]))
    return try JSONDecoder().decode(T.self, from: data)
}

@Test func finishedTaskDecodes() throws {
    let bot = try decodeWork(Bot.self, "bot")
    let task = try #require(bot.task)
    #expect(task.status == "finished")
    #expect(!task.isActive)
    #expect(task.issue?.key == "#142")
    #expect(task.pr?.number == 7)
    #expect(task.usage?.label == "$0.05")
}

@Test func learningCardDecodes() throws {
    let entry = try decodeWork(Entry.self, "learningCard")
    let learning = try #require(entry.data.learning)
    #expect(learning.files?.first?.path == "fix.txt")
    #expect(learning.posted == ["slack", "teams"])
    #expect(learning.pr?.url == "https://github.com/acme/shop/pull/7")
    #expect(TaskUsage(cost: 0.004, estimated: true).label == "≈ less than $0.01")
}
