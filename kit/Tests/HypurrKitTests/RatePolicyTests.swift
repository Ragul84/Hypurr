import Foundation
import Testing
@testable import HypurrKit

@Test func rateAsksAfterThirdFinishedTaskThenWaits() {
    let d = UserDefaults(suiteName: "rate-test-\(UUID().uuidString)")!
    let p = RatePolicy(defaults: d)
    #expect(!p.taskFinished("a"))
    #expect(!p.taskFinished("b"))
    #expect(!p.taskFinished("b"))
    #expect(p.taskFinished("c"))
    p.asked()
    #expect(!p.taskFinished("d"))
    let later = RatePolicy(defaults: d, now: { Date().addingTimeInterval(RatePolicy.gap + 1) })
    #expect(later.taskFinished("e"))
}
