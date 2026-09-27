import Foundation
import Testing
@testable import CodyncKit

@Test func pushContentAcceptsAnOptionalStatusSubtitle() throws {
    let current = try JSONDecoder().decode(PushAlert.self, from: Data(#"{"title":"Reviewer","subtitle":"Task complete","body":"All tests passed."}"#.utf8))
    #expect(current.subtitle == "Task complete")
    #expect(current.body == "All tests passed.")
    let olderHost = try JSONDecoder().decode(PushAlert.self, from: Data(#"{"title":"Reviewer","body":"All tests passed."}"#.utf8))
    #expect(olderHost.subtitle == nil)
}
