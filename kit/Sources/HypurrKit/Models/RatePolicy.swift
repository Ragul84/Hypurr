import Foundation

/// When to ask for an App Store rating: after the 3rd finished task, never twice within
/// 120 days. Same rule as Android's `RatePolicy`; Apple also throttles the system prompt.
public struct RatePolicy: @unchecked Sendable {
    public static let minTasks = 3
    public static let gap: TimeInterval = 120 * 24 * 60 * 60
    let defaults: UserDefaults
    let now: @Sendable () -> Date

    public init(defaults: UserDefaults = .standard, now: @escaping @Sendable () -> Date = { Date() }) {
        self.defaults = defaults
        self.now = now
    }

    /// Records a finished task; true when the prompt should show now.
    public func taskFinished(_ taskId: String) -> Bool {
        var seen = defaults.stringArray(forKey: "rate.finished") ?? []
        guard !seen.contains(taskId) else { return false }
        seen.append(taskId)
        defaults.set(Array(seen.suffix(50)), forKey: "rate.finished")
        guard seen.count >= Self.minTasks else { return false }
        if let last = defaults.object(forKey: "rate.askedAt") as? Date, now().timeIntervalSince(last) < Self.gap { return false }
        return true
    }

    public func asked() { defaults.set(now(), forKey: "rate.askedAt") }
}
