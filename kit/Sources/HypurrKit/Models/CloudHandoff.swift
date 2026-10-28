import Foundation

// Cloud handoff: continue a task on the agent maker's cloud with the user's own account
// (host `cloudOptions`, `cloudHandoff`, `setCursorKey`).

public struct CloudVendor: Decodable, Sendable, Identifiable, Hashable {
    public var id: String
    public var name: String
    public var ready: Bool
    public var needs: String?
}

public struct CloudOptions: Decodable, Sendable {
    public var vendors: [CloudVendor]
    public var repo: String?
    public var branch: String?
    public var unpushed: Bool?
    public var codexEnv: String?
}

public extension HostClient {
    func cloudOptions(project: String) async throws -> CloudOptions {
        try await call("cloudOptions", ["project": project], timeout: 30)
    }

    /// Starts the cloud run; returns the link to follow it.
    func cloudHandoff(project: String, vendor: String, prompt: String, codexEnv: String?) async throws -> String {
        struct R: Decodable { var url: String? }
        var body = ["project": project, "vendor": vendor, "prompt": prompt]
        if let codexEnv { body["codexEnv"] = codexEnv }
        let r: R = try await call("cloudHandoff", body, timeout: 200)
        return r.url ?? "https://github.com"
    }

    func setCursorKey(_ key: String) async throws {
        let _: Empty = try await call("setCursorKey", ["key": key])
    }
}
