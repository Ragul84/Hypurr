import Foundation

// Work accounts and the company team pool (host `work*` methods, gateway cloud/src/teams).
// Gateway amounts are US cents.

public struct WorkBalance: Decodable, Sendable {
    public struct TeamUsage: Decodable, Sendable {
        public var id: String
        public var monthlyLimitCents: Int?
        public var spentThisMonthCents: Int?
    }
    public var workEmail: String?
    public var team: TeamUsage?
    public init(workEmail: String?, team: TeamUsage?) {
        self.workEmail = workEmail
        self.team = team
    }
    public var isSignedIn: Bool { !(workEmail ?? "").isEmpty }
}

public struct WorkTeam: Decodable, Sendable {
    public struct Info: Decodable, Sendable {
        public var id: String
        public var name: String
    }
    public struct Member: Decodable, Sendable, Identifiable {
        public var userId: String
        public var email: String?
        public var role: String
        public var status: String
        public var monthlyLimitCents: Int?
        public var spentThisMonthCents: Int?
        public var id: String { userId }
        public var paused: Bool { status == "suspended" }
        public var isAdmin: Bool { role == "admin" }
    }
    public var team: Info?
    public var role: String?
    public var monthlyLimitCents: Int?
    public var spentThisMonthCents: Int?
    public var poolCents: Int?
    public var members: [Member]?
    public var isAdmin: Bool { role == "admin" }
}

public struct SsoConfig: Decodable, Sendable {
    public var provider: String
    public var clientId: String
    public var tenant: String?
    public var authorizeUrl: String
    public var tokenUrl: String

    public init(provider: String, clientId: String, tenant: String?, authorizeUrl: String, tokenUrl: String) {
        self.provider = provider
        self.clientId = clientId
        self.tenant = tenant
        self.authorizeUrl = authorizeUrl
        self.tokenUrl = tokenUrl
    }

    /// Endpoints for first-time setup, before the gateway knows the team.
    public static func forSetup(provider: String, clientId: String, tenant: String?) -> SsoConfig {
        provider == "microsoft"
            ? SsoConfig(provider: provider, clientId: clientId, tenant: tenant,
                        authorizeUrl: "https://login.microsoftonline.com/\(tenant ?? "")/oauth2/v2.0/authorize",
                        tokenUrl: "https://login.microsoftonline.com/\(tenant ?? "")/oauth2/v2.0/token")
            : SsoConfig(provider: provider, clientId: clientId, tenant: nil,
                        authorizeUrl: "https://accounts.google.com/o/oauth2/v2/auth",
                        tokenUrl: "https://oauth2.googleapis.com/token")
    }
}

/// "$12.34"; nil → "–".
public func workDollars(_ cents: Int?) -> String {
    guard let cents else { return "–" }
    return String(format: "$%.2f", Double(cents) / 100)
}

struct WorkMemberPatch: Encodable {
    var userId: String
    var role: String?
    var status: String?
    /// Outer nil: unchanged; inner nil: remove the limit.
    var monthlyLimitCents: Int??

    private enum Keys: String, CodingKey { case userId, role, status, monthlyLimitCents }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Keys.self)
        try c.encode(userId, forKey: .userId)
        try c.encodeIfPresent(role, forKey: .role)
        try c.encodeIfPresent(status, forKey: .status)
        if let limit = monthlyLimitCents {
            if let limit { try c.encode(limit, forKey: .monthlyLimitCents) } else { try c.encodeNil(forKey: .monthlyLimitCents) }
        }
    }
}

struct WorkSignInBody: Encodable {
    var email: String
    var idToken: String
    var name: String?
    var provider: String?
    var domain: String?
    var clientId: String?
    var tenant: String?
}

public extension HostClient {
    func gatewayBalance() async throws -> WorkBalance { try await call("gatewayBalance", timeout: 30) }
    func workSsoConfig(email: String) async throws -> SsoConfig { try await call("workSsoConfig", ["email": email], timeout: 30) }
    func workTeam() async throws -> WorkTeam { try await call("workTeam", timeout: 30) }
    func workSignOut() async throws { let _: Empty = try await call("workSignOut") }

    /// After the company sign-in: join (or, with `setup`, create) the team; the computer keeps the key.
    func workSignIn(email: String, idToken: String, setup: (name: String, provider: String, clientId: String, tenant: String?)? = nil) async throws {
        let domain = email.split(separator: "@").last.map(String.init)
        let body = WorkSignInBody(email: email, idToken: idToken, name: setup?.name, provider: setup?.provider,
                                  domain: setup == nil ? nil : domain, clientId: setup?.clientId, tenant: setup?.tenant)
        struct Res: Decodable {}
        let _: Res = try await call(setup == nil ? "workSignIn" : "workTeamSetup", body, timeout: 60)
    }

    func workTeamMember(_ userId: String, role: String? = nil, status: String? = nil, monthlyLimitCents: Int?? = nil) async throws {
        struct Res: Decodable {}
        let _: Res = try await call("workTeamMember", WorkMemberPatch(userId: userId, role: role, status: status, monthlyLimitCents: monthlyLimitCents), timeout: 30)
    }

    func workTeamPool(amountCents: Int) async throws {
        struct Res: Decodable {}
        let _: Res = try await call("workTeamPool", ["amountCents": amountCents], timeout: 30)
    }
}
