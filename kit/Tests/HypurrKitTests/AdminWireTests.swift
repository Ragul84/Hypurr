import Foundation
import Testing
@testable import HypurrKit

/// docs/reference/fixtures/admin-wire.json: real `hypurr-host` output for team admin (shared with Android).
private let adminWireData: Data = {
    let url = URL(filePath: #filePath).deletingLastPathComponent()
        .appending(path: "../../../docs/reference/fixtures/admin-wire.json").standardized
    return (try? Data(contentsOf: url)) ?? Data()
}()

private func adminObject(_ key: String) throws -> Data {
    let wire = try #require(try JSONSerialization.jsonObject(with: adminWireData) as? [String: Any])
    return try JSONSerialization.data(withJSONObject: try #require(wire[key]))
}

@Test func helloCarriesYourRole() throws {
    let hello = try #require(try JSONSerialization.jsonObject(with: try adminObject("hello")) as? [String: Any])
    let you = try JSONDecoder().decode(TeamActor.self, from: JSONSerialization.data(withJSONObject: try #require(hello["you"])))
    #expect(you.name == "Priya's phone")
    #expect(you.role == "viewer")
    #expect(!you.isAdmin)
    #expect(!you.canAct)
}

@Test func cardNeedsAnAdmin() throws {
    let card = try JSONDecoder().decode(Entry.self, from: try adminObject("card"))
    #expect(card.data.needsAdmin == true)
    #expect(card.data.risk == "low")
}
