import HypurrKit
import SwiftUI
import WebKit

/// Pages load from `hypurr-preview://localhost:<port>/…`; every request is fetched on the
/// computer from `http://localhost:<port>` over the encrypted channel. Relative links and
/// scripts work; absolute `http://localhost` URLs inside the page don't.
public enum PreviewURL {
    public static let scheme = "hypurr-preview"
    public static func start(_ port: Int) -> URL { URL(string: "\(scheme)://localhost:\(port)/")! }

    /// Path and query to ask the dev server for.
    public static func path(_ url: URL) -> String {
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let p = (c?.percentEncodedPath).flatMap { $0.isEmpty ? nil : $0 } ?? "/"
        return p + (c?.percentEncodedQuery.map { "?\($0)" } ?? "")
    }
}

@MainActor
final class PreviewSchemeHandler: NSObject, WKURLSchemeHandler {
    let client: HostClient
    let botId: String
    let port: Int
    let onError: (String?) -> Void
    private var live = Set<ObjectIdentifier>()

    init(client: HostClient, botId: String, port: Int, onError: @escaping (String?) -> Void) {
        self.client = client
        self.botId = botId
        self.port = port
        self.onError = onError
    }

    func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        let id = ObjectIdentifier(task)
        live.insert(id)
        let request = task.request
        guard let url = request.url else { return }
        let headers = request.allHTTPHeaderFields ?? [:]
        let method = request.httpMethod ?? "GET"
        let body = request.httpBody
        let (client, botId, port) = (client, botId, port)
        Task { @MainActor in
            let result: Result<PreviewResponse, Error>
            do {
                result = .success(try await client.previewFetch(botId: botId, port: port, path: PreviewURL.path(url),
                                                                method: method, headers: headers, body: body))
            } catch {
                result = .failure(error)
            }
            guard live.remove(id) != nil else { return }
            switch result {
            case .success(let r):
                if let res = HTTPURLResponse(url: url, statusCode: r.status, httpVersion: "HTTP/1.1", headerFields: r.headers) {
                    task.didReceive(res)
                    task.didReceive(r.body)
                    task.didFinish()
                }
                onError(nil)
            case .failure(let e):
                task.didFailWithError(e)
                onError(e.localizedDescription)
            }
        }
    }

    func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {
        live.remove(ObjectIdentifier(task))
    }
}

#if os(iOS)
    struct PreviewWeb: UIViewRepresentable {
        let handler: PreviewSchemeHandler
        let reload: Int
        func makeUIView(context: Context) -> WKWebView { makeWeb(handler) }
        func updateUIView(_ view: WKWebView, context: Context) { if reload > 0 { view.reload() } }
    }
#else
    struct PreviewWeb: NSViewRepresentable {
        let handler: PreviewSchemeHandler
        let reload: Int
        func makeNSView(context: Context) -> WKWebView { makeWeb(handler) }
        func updateNSView(_ view: WKWebView, context: Context) { if reload > 0 { view.reload() } }
    }
#endif

@MainActor private func makeWeb(_ handler: PreviewSchemeHandler) -> WKWebView {
    let config = WKWebViewConfiguration()
    config.setURLSchemeHandler(handler, forURLScheme: PreviewURL.scheme)
    config.websiteDataStore = .nonPersistent()
    let web = WKWebView(frame: .zero, configuration: config)
    web.load(URLRequest(url: PreviewURL.start(handler.port)))
    return web
}

/// Full live preview of `localhost:<port>` on the computer.
public struct PreviewView: View {
    let port: Int
    let close: () -> Void
    @State private var handler: PreviewSchemeHandler?
    @State private var error: String?
    @State private var reload = 0
    private let client: HostClient
    private let botId: String

    public init(client: HostClient, botId: String, port: Int, close: @escaping () -> Void) {
        self.client = client
        self.botId = botId
        self.port = port
        self.close = close
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack {
                IconButton("Close preview", systemImage: "chevron.left", action: close)
                Text("localhost:\(port)").lineLimit(1)
                Spacer()
                Button("Reload") { error = nil; reload += 1 }
            }
            .padding(.horizontal, 12)
            .frame(height: 44)
            if let error { Text(error).font(.caption).foregroundStyle(Palette.danger).padding(.horizontal, 12) }
            Rectangle().fill(Palette.border).frame(height: 0.5)
            if let handler { PreviewWeb(handler: handler, reload: reload) } else { Spacer() }
        }
        .background(Palette.background)
        .onAppear {
            if handler == nil {
                handler = PreviewSchemeHandler(client: client, botId: botId, port: port) { error = $0 }
            }
        }
    }
}

/// "Preview localhost:<port>" buttons for dev servers this bot started (none when there are none).
struct PreviewButtons: View {
    let botId: String
    @Environment(BotStore.self) private var model
    @State private var ports: [Int] = []
    @State private var open: Int?

    var body: some View {
        VStack(spacing: 6) {
            ForEach(ports, id: \.self) { p in
                Button("Preview localhost:\(p)") { open = p }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
        }
        .task(id: model.bots[botId]?.isWorking) {
            guard let client = model.client else { return }
            ports = (try? await client.previewPorts(botId: botId)) ?? []
        }
        .hypurrSheet(isPresented: Binding(get: { open != nil }, set: { if !$0 { open = nil } })) {
            if let p = open, let client = model.client {
                PreviewView(client: client, botId: botId, port: p) { open = nil }
                    #if os(macOS)
                        .frame(width: 900, height: 640)
                    #endif
            }
        }
    }
}
