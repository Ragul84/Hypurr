import Foundation

// Live preview: dev servers a bot started, fetched on the computer from loopback and carried
// over the encrypted channel (host `previewPorts`, `previewFetch`).

public struct PreviewSlice: Decodable, Sendable {
    public var status: Int
    public var headers: [[String]]
    public var body: String
    public var offset: Int
    public var total: Int
}

public struct PreviewResponse: Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data
}

struct PreviewRequest: Encodable {
    var botId: String
    var port: Int
    var path: String
    var method: String
    var headers: [[String]]
    var body: String?
    var offset: Int
}

public extension HostClient {
    /// Dev servers this bot started that are up now.
    func previewPorts(botId: String) async throws -> [Int] {
        struct P: Decodable { var port: Int }
        struct R: Decodable { var previews: [P] }
        let r: R = try await call("previewPorts", ["botId": botId])
        return r.previews.map(\.port)
    }

    /// One whole response from the dev server, paged in slices.
    func previewFetch(botId: String, port: Int, path: String, method: String = "GET",
                      headers: [String: String] = [:], body: Data? = nil) async throws -> PreviewResponse {
        var data = Data()
        var first: PreviewSlice?
        var offset = 0
        repeat {
            let req = PreviewRequest(botId: botId, port: port, path: path, method: method,
                                     headers: headers.map { [$0.key, $0.value] },
                                     body: offset == 0 ? body?.base64EncodedString() : nil, offset: offset)
            let s: PreviewSlice = try await call("previewFetch", req, timeout: 45)
            if first == nil { first = s }
            let chunk = Data(base64Encoded: s.body) ?? Data()
            data.append(chunk)
            offset = s.offset + chunk.count
            if chunk.isEmpty { break }
        } while offset < (first?.total ?? 0)
        var h: [String: String] = [:]
        for pair in first?.headers ?? [] where pair.count == 2 { h[pair[0]] = pair[1] }
        return PreviewResponse(status: first?.status ?? 502, headers: h, body: data)
    }
}
