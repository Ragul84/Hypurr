import Foundation
import Testing
@testable import HypurrKit
@testable import HypurrUI

/// The chat shows user messages and final replies; while a turn runs, only the text being
/// generated right now joins them. Earlier narration and room passes never do.
@Test func chatShowsOnlyFinalRepliesAndTheTextBeingGenerated() {
    func entry(_ seq: Int64, _ kind: String, _ text: String? = nil, final: Bool? = nil) -> Entry {
        var data = EntryData(text: text)
        data.final = final
        return Entry(id: "e\(seq)", seq: seq, botId: "b", rev: seq, kind: kind, turn: 1, data: data, createdAt: 0, updatedAt: 0)
    }
    let ids = { (items: [ChatItem]) in items.compactMap { if case let .entry(e, _) = $0.kind { e.id } else { nil } } }

    // Mid-turn: narration closed by a tool call, then the answer streaming.
    let running = [entry(1, "user", "Fix it"), entry(2, "agent", "I'll read it first.", final: false),
                   entry(3, "tool_call", "Read"), entry(4, "agent", "Done. It raced", final: false)]
    #expect(ids(ChatItem.build(running, streaming: true)) == ["e1", "e4"])
    #expect(ids(ChatItem.build(running)) == ["e1"])
    // Idle: the last text has been marked final; narration stays trace-only.
    let done = [running[0], running[1], running[2], entry(4, "agent", "Done. It raced the refresh.", final: true)]
    #expect(ids(ChatItem.build(done)) == ["e1", "e4"])
    // A room pass is being generated: still not chat.
    #expect(ids(ChatItem.build([running[0], entry(2, "agent", "(pass)", final: false)], streaming: true)) == ["e1"])
}
