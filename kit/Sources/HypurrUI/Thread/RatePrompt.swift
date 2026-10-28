import HypurrKit
import StoreKit
import SwiftUI

/// Asks for an App Store rating (Apple's own prompt) when a task finishes, per `RatePolicy`.
struct RatePrompt: ViewModifier {
    let taskId: String?
    let status: String?
    @Environment(\.requestReview) private var requestReview

    func body(content: Content) -> some View {
        content.onChange(of: status) { _, new in
            guard new == "finished", let taskId else { return }
            let policy = RatePolicy()
            if policy.taskFinished(taskId) {
                policy.asked()
                requestReview()
            }
        }
    }
}
