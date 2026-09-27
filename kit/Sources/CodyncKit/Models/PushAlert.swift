import Foundation

/// Private notification content. Only the host and the device can read these fields.
public struct PushAlert: Decodable, Sendable {
    public let title: String
    public let subtitle: String?
    public let body: String
}
