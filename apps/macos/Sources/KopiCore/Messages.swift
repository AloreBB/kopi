import Foundation

// Protocol message models. The wire format is normative in protocol/PROTOCOL.md
// and pinned by protocol/fixtures/*.json. The "type" key discriminates cases.
// UUIDs are encoded lowercase, matching the fixtures.
public enum KopiMessage: Codable, Equatable, Sendable {
    case hello(Hello)
    case paired(Paired)
    case clip(Clip)
    case clipOffer(ClipOffer)
    case clipRequest(ClipRequest)
    case clipData(ClipData)
    case clipUnavailable(ClipUnavailable)
    case error(KopiError)

    private enum TypeTag: String, Codable {
        case hello
        case paired
        case clip
        case clipOffer = "clip_offer"
        case clipRequest = "clip_request"
        case clipData = "clip_data"
        case clipUnavailable = "clip_unavailable"
        case error
    }

    private enum CodingKeys: String, CodingKey {
        case type
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        switch try container.decode(TypeTag.self, forKey: .type) {
        case .hello: self = .hello(try Hello(from: decoder))
        case .paired: self = .paired(try Paired(from: decoder))
        case .clip: self = .clip(try Clip(from: decoder))
        case .clipOffer: self = .clipOffer(try ClipOffer(from: decoder))
        case .clipRequest: self = .clipRequest(try ClipRequest(from: decoder))
        case .clipData: self = .clipData(try ClipData(from: decoder))
        case .clipUnavailable: self = .clipUnavailable(try ClipUnavailable(from: decoder))
        case .error: self = .error(try KopiError(from: decoder))
        }
    }

    public func encode(to encoder: Encoder) throws {
        // Flatten manually: the discriminator and the payload live in one object.
        var container = encoder.container(keyedBy: FieldKey.self)
        switch self {
        case .hello(let value):
            try container.encode(TypeTag.hello, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        case .paired(let value):
            try container.encode(TypeTag.paired, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        case .clip(let value):
            try container.encode(TypeTag.clip, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        case .clipOffer(let value):
            try container.encode(TypeTag.clipOffer, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        case .clipRequest(let value):
            try container.encode(TypeTag.clipRequest, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        case .clipData(let value):
            try container.encode(TypeTag.clipData, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        case .clipUnavailable(let value):
            try container.encode(TypeTag.clipUnavailable, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        case .error(let value):
            try container.encode(TypeTag.error, forKey: FieldKey("type"))
            try value.encodeFields(to: &container)
        }
    }
}

// Open-ended key for the manual encoders: field names are fixed strings.
fileprivate struct FieldKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }

    init(_ string: String) {
        stringValue = string
    }

    init?(stringValue: String) {
        self.stringValue = stringValue
    }

    init?(intValue: Int) {
        nil
    }
}

public struct Hello: Codable, Equatable, Sendable {
    public var protocolVersion: Int
    public var app: String
    /// Display name of the sending device (required; used for trust-on-first-use pairing).
    public var name: String

    public init(protocolVersion: Int, app: String, name: String) {
        self.protocolVersion = protocolVersion
        self.app = app
        self.name = name
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(protocolVersion, forKey: FieldKey("protocolVersion"))
        try container.encode(app, forKey: FieldKey("app"))
        try container.encode(name, forKey: FieldKey("name"))
    }
}

public struct Paired: Codable, Equatable, Sendable {
    public var name: String

    public init(name: String) {
        self.name = name
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(name, forKey: FieldKey("name"))
    }
}

public enum ClipKind: String, Codable, Equatable, Sendable {
    case text
    case image
}

public struct Clip: Codable, Equatable, Sendable {
    public var id: UUID
    public var ts: Int64
    public var kind: ClipKind
    public var text: String
    public var sensitive: Bool

    public init(id: UUID, ts: Int64, kind: ClipKind, text: String, sensitive: Bool) {
        self.id = id
        self.ts = ts
        self.kind = kind
        self.text = text
        self.sensitive = sensitive
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(id.uuidString.lowercased(), forKey: FieldKey("id"))
        try container.encode(ts, forKey: FieldKey("ts"))
        try container.encode(kind, forKey: FieldKey("kind"))
        try container.encode(text, forKey: FieldKey("text"))
        try container.encode(sensitive, forKey: FieldKey("sensitive"))
    }
}

public struct ClipOffer: Codable, Equatable, Sendable {
    public var id: UUID
    public var ts: Int64
    public var kind: ClipKind
    public var mime: String
    public var size: Int

    public init(id: UUID, ts: Int64, kind: ClipKind, mime: String, size: Int) {
        self.id = id
        self.ts = ts
        self.kind = kind
        self.mime = mime
        self.size = size
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(id.uuidString.lowercased(), forKey: FieldKey("id"))
        try container.encode(ts, forKey: FieldKey("ts"))
        try container.encode(kind, forKey: FieldKey("kind"))
        try container.encode(mime, forKey: FieldKey("mime"))
        try container.encode(size, forKey: FieldKey("size"))
    }
}

public struct ClipRequest: Codable, Equatable, Sendable {
    public var id: UUID

    public init(id: UUID) {
        self.id = id
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(id.uuidString.lowercased(), forKey: FieldKey("id"))
    }
}

public struct ClipData: Codable, Equatable, Sendable {
    public var id: UUID
    public var size: Int

    public init(id: UUID, size: Int) {
        self.id = id
        self.size = size
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(id.uuidString.lowercased(), forKey: FieldKey("id"))
        try container.encode(size, forKey: FieldKey("size"))
    }
}

public enum UnavailableReason: String, Codable, Equatable, Sendable {
    case expired
    case replaced
}

public struct ClipUnavailable: Codable, Equatable, Sendable {
    public var id: UUID
    public var reason: UnavailableReason

    public init(id: UUID, reason: UnavailableReason) {
        self.id = id
        self.reason = reason
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(id.uuidString.lowercased(), forKey: FieldKey("id"))
        try container.encode(reason, forKey: FieldKey("reason"))
    }
}

public enum ErrorCode: String, Codable, Equatable, Sendable {
    case versionMismatch = "version_mismatch"
    case notPaired = "not_paired"
}

public struct KopiError: Codable, Equatable, Sendable {
    public var code: ErrorCode

    public init(code: ErrorCode) {
        self.code = code
    }

    fileprivate func encodeFields(to container: inout KeyedEncodingContainer<FieldKey>) throws {
        try container.encode(code, forKey: FieldKey("code"))
    }
}
