import Foundation

// MARK: - Session storage

/// Where `RemoteBackendClient` reads the session before every request and writes it back after a refresh
/// (rotated pair) or when the refresh was rejected (`nil`). Reads must be synchronous and cheap.
struct SessionStorage: Sendable {
    var read: @Sendable () -> Session?
    var write: @Sendable (Session?) -> Void
}

// MARK: - Wire dates

/// "Day" fields go to the server as `YYYY-MM-DD` in the device calendar, so the calendar day never shifts
/// with the server's idea of the user's time zone.
enum WireDay {
    static func string(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    static func date(_ string: String, calendar: Calendar = .current) -> Date? {
        let parts = string.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}

/// Decodes ISO-8601 instants with or without fractional seconds, plus bare `YYYY-MM-DD` as local midnight.
enum WireDateDecoding {
    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
    private static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func date(from string: String) -> Date? {
        plain.date(from: string) ?? fractional.date(from: string) ?? WireDay.date(string)
    }

    static let strategy: JSONDecoder.DateDecodingStrategy = .custom { decoder in
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        guard let date = date(from: raw) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unrecognised date \(raw)")
        }
        return date
    }
}

// MARK: - Multipart

/// Minimal multipart/form-data writer.
struct MultipartBody {
    let boundary: String
    private var parts: [Data] = []

    init(boundary: String = "NoTomorrow-\(UUID().uuidString)") { self.boundary = boundary }

    var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    func field(_ name: String, _ value: String) -> MultipartBody {
        var copy = self
        var d = Data()
        d.append("--\(boundary)\r\n")
        d.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n")
        d.append("\(value)\r\n")
        copy.parts.append(d)
        return copy
    }

    func file(_ name: String, filename: String, mimeType: String, data: Data) -> MultipartBody {
        var copy = self
        var d = Data()
        d.append("--\(boundary)\r\n")
        d.append("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n")
        d.append("Content-Type: \(mimeType)\r\n\r\n")
        d.append(data)
        d.append("\r\n")
        copy.parts.append(d)
        return copy
    }

    func encoded() -> Data {
        var out = Data()
        parts.forEach { out.append($0) }
        out.append("--\(boundary)--\r\n")
        return out
    }
}

private extension Data {
    mutating func append(_ string: String) { append(Data(string.utf8)) }
}

// MARK: - Transport

/// HTTP plumbing for the Fly.io backend: JSON encoding, the Bearer header, the error envelope and the
/// refresh-on-401 dance. Endpoint methods live in `RemoteBackendClient`.
final class RemoteTransport: @unchecked Sendable {
    let baseURL: URL
    let storage: SessionStorage
    private let urlSession: URLSession
    private let refresher = TokenRefresher()
    let encoder: JSONEncoder
    let decoder: JSONDecoder

    init(baseURL: URL, storage: SessionStorage, urlSession: URLSession = .shared) {
        self.baseURL = baseURL
        self.storage = storage
        self.urlSession = urlSession
        encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = WireDateDecoding.strategy
    }

    /// Public auth routes never carry a token and a 401 there is a plain server reply, not a session problem.
    enum Auth { case none, required }

    enum Payload {
        case empty
        case json(Data)
        case multipart(MultipartBody)
    }

    struct Request {
        var method: String
        var path: String
        var auth: Auth = .required
        var payload: Payload = .empty
        var headers: [String: String] = [:]
        var timeout: TimeInterval = 30
    }

    // MARK: Convenience

    func send<T: Decodable>(_ method: String, _ path: String, auth: Auth = .required) async throws -> T {
        try await decode(perform(Request(method: method, path: path, auth: auth)))
    }

    func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, auth: Auth = .required, json body: B) async throws -> T {
        try await decode(perform(Request(method: method, path: path, auth: auth, payload: .json(try encoder.encode(body)))))
    }

    func sendVoid(_ method: String, _ path: String, auth: Auth = .required) async throws {
        _ = try await perform(Request(method: method, path: path, auth: auth))
    }

    func sendVoid<B: Encodable>(_ method: String, _ path: String, auth: Auth = .required, json body: B) async throws {
        _ = try await perform(Request(method: method, path: path, auth: auth, payload: .json(try encoder.encode(body))))
    }

    func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try decoder.decode(T.self, from: data) } catch { throw BackendError.decoding }
    }

    // MARK: Core

    /// Runs the request. With `auth: .required`: no session → `unauthorized` without touching the network;
    /// a 401 → one refresh (coalesced across concurrent callers) and one retry; a failed refresh clears the session.
    func perform(_ request: Request) async throws -> Data {
        switch request.auth {
        case .none:
            return try await execute(request, token: nil).data
        case .required:
            guard let session = storage.read() else { throw BackendError.unauthorized }
            let first = try await execute(request, token: session.accessToken)
            guard first.status == 401 else { return try unwrap(first, auth: .required) }

            let refreshed = try await refresher.refresh(after: session.accessToken, storage: storage) { [self] refreshToken in
                await rotate(refreshToken)
            }
            guard let refreshed else { throw BackendError.unauthorized }
            let second = try await execute(request, token: refreshed.accessToken)
            if second.status == 401 {
                storage.write(nil)
                throw BackendError.unauthorized
            }
            return try unwrap(second, auth: .required)
        }
    }

    private struct Reply {
        var status: Int
        var data: Data
    }

    private func execute(_ request: Request, token: String?) async throws -> Reply {
        var urlRequest = URLRequest(url: baseURL.appending(path: request.path))
        urlRequest.httpMethod = request.method
        urlRequest.timeoutInterval = request.timeout
        urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        urlRequest.setValue("NoTomorrow/0.1 iOS", forHTTPHeaderField: "User-Agent")
        if let token { urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        for (key, value) in request.headers { urlRequest.setValue(value, forHTTPHeaderField: key) }
        switch request.payload {
        case .empty:
            break
        case .json(let data):
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
            urlRequest.httpBody = data
        case .multipart(let body):
            urlRequest.setValue(body.contentType, forHTTPHeaderField: "Content-Type")
            urlRequest.httpBody = body.encoded()
        }

        let (data, response): (Data, URLResponse)
        do { (data, response) = try await urlSession.data(for: urlRequest) } catch { throw BackendError.network }
        guard let http = response as? HTTPURLResponse else { throw BackendError.network }
        let reply = Reply(status: http.statusCode, data: data)
        return request.auth == .none ? Reply(status: reply.status, data: try unwrap(reply, auth: .none)) : reply
    }

    /// 2xx → body; anything else → the server's `{error, message}` envelope as `BackendError.http`.
    private func unwrap(_ reply: Reply, auth: Auth) throws -> Data {
        if (200..<300).contains(reply.status) { return reply.data }
        struct Envelope: Decodable { var error: String?; var message: String? }
        let parsed = try? decoder.decode(Envelope.self, from: reply.data)
        let code = parsed?.error ?? "http_\(reply.status)"
        let message = parsed?.message ?? parsed?.error ?? "HTTP \(reply.status)"
        throw BackendError.http(status: reply.status, code: code, message: message)
    }

    // MARK: Refresh

    enum RefreshOutcome {
        case rotated(Session)
        /// The server rejected the refresh token (expired, revoked, reused): the session is gone.
        case rejected
        /// Could not reach the server: keep the session and let the caller report `network`.
        case unreachable
    }

    private func rotate(_ refreshToken: String) async -> RefreshOutcome {
        let body: Data
        do { body = try encoder.encode(["refreshToken": refreshToken]) } catch { return .unreachable }
        let request = Request(method: "POST", path: "auth/refresh", auth: .none, payload: .json(body))
        do {
            let data = try await execute(request, token: nil).data
            let session = try decoder.decode(Session.self, from: data)
            return .rotated(session)
        } catch BackendError.http(let status, _, _) where status == 401 || status == 400 {
            return .rejected
        } catch {
            return .unreachable
        }
    }
}

// MARK: - Refresh coordination

/// Serialises refreshes so concurrent 401s rotate the pair once. A caller whose token is already stale
/// (someone else refreshed meanwhile) gets the current session without another round-trip; presenting a
/// rotated refresh token twice would otherwise revoke the whole family server-side.
actor TokenRefresher {
    private var inFlight: Task<RemoteTransport.RefreshOutcome, Never>?

    func refresh(after failedAccessToken: String, storage: SessionStorage,
                 rotate: @escaping @Sendable (String) async -> RemoteTransport.RefreshOutcome) async throws -> Session? {
        if let current = storage.read(), current.accessToken != failedAccessToken { return current }
        if let inFlight { return try resolve(await inFlight.value) }
        guard let refreshToken = storage.read()?.refreshToken else { return nil }
        let task = Task<RemoteTransport.RefreshOutcome, Never> {
            let outcome = await rotate(refreshToken)
            switch outcome {
            case .rotated(let session): storage.write(session)
            case .rejected: storage.write(nil)
            case .unreachable: break
            }
            return outcome
        }
        inFlight = task
        let outcome = await task.value
        inFlight = nil
        return try resolve(outcome)
    }

    private func resolve(_ outcome: RemoteTransport.RefreshOutcome) throws -> Session? {
        switch outcome {
        case .rotated(let session): return session
        case .rejected: return nil
        case .unreachable: throw BackendError.network
        }
    }
}
