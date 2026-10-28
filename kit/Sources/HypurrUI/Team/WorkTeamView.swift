import AuthenticationServices
import CryptoKit
import HypurrKit
import SwiftUI

/// Company sign-in: authorization code + PKCE in the system browser sheet, redirect
/// `hypurr://oauth/work`. The phone or Mac trades the code for the ID token itself (public
/// client, no secret) and hands the token to the computer, which keeps the work key.
enum WorkOAuth {
    static let redirect = "hypurr://oauth/work"

    static func random() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return b64url(Data(bytes))
    }

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func challenge(_ verifier: String) -> String { b64url(Data(SHA256.hash(data: Data(verifier.utf8)))) }

    static func authorizeURL(_ cfg: SsoConfig, email: String, state: String, verifier: String) -> URL? {
        var c = URLComponents(string: cfg.authorizeUrl)
        var q = [
            URLQueryItem(name: "client_id", value: cfg.clientId),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "redirect_uri", value: redirect),
            URLQueryItem(name: "scope", value: "openid email profile"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: challenge(verifier)),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "login_hint", value: email),
            URLQueryItem(name: "prompt", value: "select_account"),
        ]
        if cfg.provider == "google", let domain = email.split(separator: "@").last { q.append(URLQueryItem(name: "hd", value: String(domain))) }
        c?.queryItems = q
        return c?.url
    }

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static func code(from url: URL, state: String) throws -> String {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func v(_ n: String) -> String? { items.first { $0.name == n }?.value }
        if v("error") != nil { throw Failure(message: v("error_description") ?? "Sign-in was cancelled.") }
        guard v("state") == state else { throw Failure(message: "That sign-in didn't come from this app. Try again.") }
        guard let code = v("code") else { throw Failure(message: "Sign-in was cancelled.") }
        return code
    }

    static func idToken(_ cfg: SsoConfig, code: String, verifier: String) async throws -> String {
        guard let url = URL(string: cfg.tokenUrl) else { throw Failure(message: "Bad sign-in settings.") }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var form = URLComponents()
        form.queryItems = [
            URLQueryItem(name: "grant_type", value: "authorization_code"), URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "redirect_uri", value: redirect), URLQueryItem(name: "client_id", value: cfg.clientId),
            URLQueryItem(name: "code_verifier", value: verifier),
        ]
        req.httpBody = Data((form.percentEncodedQuery ?? "").utf8)
        let (data, _) = try await URLSession.shared.data(for: req)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        if let token = json?["id_token"] as? String { return token }
        throw Failure(message: (json?["error_description"] as? String) ?? "Your company's sign-in didn't finish.")
    }
}

/// Work account: sign in with the company account; admins see the pool and people.
public struct WorkTeamView: View {
    let client: HostClient
    @Environment(\.webAuthenticationSession) private var webAuth
    @State private var balance: WorkBalance?
    @State private var team: WorkTeam?
    @State private var email = ""
    @State private var setup = false
    @State private var teamName = ""
    @State private var microsoft = false
    @State private var clientId = ""
    @State private var tenant = ""
    @State private var pool = ""
    @State private var editing: String?
    @State private var limit = ""
    @State private var busy = false
    @State private var error: String?

    public init(client: HostClient) { self.client = client }

    public var body: some View {
        CardForm {
            if let balance, balance.isSignedIn {
                signedIn(balance)
            } else if balance != nil {
                signedOut
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
            if let error { Text(error).font(.caption).foregroundStyle(Palette.danger) }
        }
        .navigationTitle("Work account")
        .task { await load() }
    }

    private var signedOut: some View {
        CardSection("Sign in with work account",
                    footer: "Use your company's Google or Microsoft account. Hypurr is then paid from your team's credits.") {
            TextField("you@company.com", text: $email).textFieldStyle(.plain).plainTextInput()
            Toggle("Set up Hypurr for my company (IT)", isOn: $setup).toggleStyle(.hypurr)
            if setup {
                TextField("Team name", text: $teamName).textFieldStyle(.plain)
                Picker("Provider", selection: $microsoft) {
                    Text("Google").tag(false)
                    Text("Microsoft").tag(true)
                }
                .pickerStyle(.segmented)
                TextField("OAuth client id", text: $clientId).textFieldStyle(.plain).plainTextInput()
                if microsoft { TextField("Tenant id", text: $tenant).textFieldStyle(.plain).plainTextInput() }
                Text("The client id comes from your company's Google or Microsoft admin console. You become the team's admin.")
                    .font(.caption).foregroundStyle(Palette.tertiary)
            }
            Button(busy ? "Opening…" : (setup ? "Sign in and create team" : "Continue")) { Task { await signIn() } }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(busy || !email.contains("@"))
        }
    }

    @ViewBuilder private func signedIn(_ b: WorkBalance) -> some View {
        CardSection(team?.team?.name ?? "Your team") {
            Text("Signed in as \(b.workEmail ?? "")").foregroundStyle(Palette.secondary)
            let used = workDollars(team?.spentThisMonthCents ?? 0)
            Text(team?.monthlyLimitCents.map { "\(used) of \(workDollars($0)) used this month" } ?? "\(used) used this month")
                .foregroundStyle(Palette.secondary)
        }
        if let team, team.isAdmin {
            CardSection("Team credits", footer: "Move credits you bought into the team pool. Everyone's use comes out of it.") {
                Text(workDollars(team.poolCents)).font(.title2.weight(.semibold))
                HStack {
                    TextField("Amount in $", text: $pool).textFieldStyle(.plain)
                    Button("Add") {
                        if let c = cents(pool), c > 0 { Task { await run { try await client.workTeamPool(amountCents: c) }; pool = "" } }
                    }
                }
            }
            CardSection("People") {
                ForEach(team.members ?? []) { m in member(m) }
            }
        }
        Button("Sign out of work account") { Task { await run { try await client.workSignOut() } } }
            .foregroundStyle(Palette.danger)
    }

    @ViewBuilder private func member(_ m: WorkTeam.Member) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(m.email ?? m.userId)
                Spacer()
                Text(m.paused ? "Paused" : (m.isAdmin ? "Admin" : "Member"))
                    .font(.caption).foregroundStyle(m.paused ? Palette.danger : Palette.secondary)
            }
            Text("\(workDollars(m.spentThisMonthCents ?? 0)) this month · " + (m.monthlyLimitCents.map { "limit \(workDollars($0))" } ?? "no limit"))
                .font(.caption).foregroundStyle(Palette.tertiary)
            if !m.isAdmin {
                if editing == m.userId {
                    HStack {
                        TextField("Monthly limit in $ (empty = none)", text: $limit).textFieldStyle(.plain)
                        Button("Save") {
                            let c = cents(limit)
                            editing = nil
                            Task { await run { try await client.workTeamMember(m.userId, monthlyLimitCents: .some(c)) } }
                        }
                    }
                } else {
                    HStack {
                        Button("Limit") { editing = m.userId; limit = "" }
                        Button(m.paused ? "Resume" : "Pause") {
                            Task { await run { try await client.workTeamMember(m.userId, status: m.paused ? "active" : "suspended") } }
                        }
                        Button("Make admin") { Task { await run { try await client.workTeamMember(m.userId, role: "admin") } } }
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private func cents(_ s: String) -> Int? {
        Double(s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "$", with: "")).flatMap { $0 >= 0 ? Int(($0 * 100).rounded()) : nil }
    }

    private func load() async {
        balance = (try? await client.gatewayBalance()) ?? WorkBalance(workEmail: nil, team: nil)
        if balance?.isSignedIn == true {
            do { team = try await client.workTeam() } catch { self.error = error.localizedDescription }
        } else {
            team = nil
        }
    }

    private func run(_ op: () async throws -> Void) async {
        error = nil
        do { try await op() } catch { self.error = error.localizedDescription }
        await load()
    }

    private func signIn() async {
        busy = true
        error = nil
        defer { busy = false }
        let mail = email.trimmingCharacters(in: .whitespaces).lowercased()
        do {
            let cfg = setup
                ? SsoConfig.forSetup(provider: microsoft ? "microsoft" : "google", clientId: clientId.trimmingCharacters(in: .whitespaces),
                                     tenant: microsoft ? tenant.trimmingCharacters(in: .whitespaces) : nil)
                : try await client.workSsoConfig(email: mail)
            let (state, verifier) = (WorkOAuth.random(), WorkOAuth.random())
            guard let url = WorkOAuth.authorizeURL(cfg, email: mail, state: state, verifier: verifier) else { return }
            let back = try await webAuth.authenticate(using: url, callbackURLScheme: "hypurr")
            let code = try WorkOAuth.code(from: back, state: state)
            let token = try await WorkOAuth.idToken(cfg, code: code, verifier: verifier)
            try await client.workSignIn(email: mail, idToken: token,
                                        setup: setup ? (teamName, cfg.provider, cfg.clientId, cfg.tenant) : nil)
            await load()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
