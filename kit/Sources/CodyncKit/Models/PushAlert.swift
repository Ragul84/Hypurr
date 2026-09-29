import Foundation

/// Private notification content. Only the host and the device can read these fields.
public struct PushAlert: Decodable, Sendable {
    public let title: String
    public let subtitle: String?
    public let body: String
    /// The member bot speaking, when the alert is for a group chat.
    public let from: String?
}
