import CryptoKit
import Foundation
import Security
import X509

// The Mac's TLS identity: a P-256 key and a self-signed certificate, both
// stored in the Keychain under the same label.
public struct LocalIdentity {
    public let secIdentity: SecIdentity
    public let certificateDER: Data
    public var fingerprint: String
}

public enum IdentityError: Error, Equatable {
    case keychain(operation: String, status: OSStatus)
    case keyCreation(String)
}

public enum Identity {
    public static let defaultLabel = "\(KopiNetConstants.bundleID).identity"
    static let commonName = "Kopi Mac"
    static let validityYears = 10

    public static func loadOrCreate(label: String = defaultLabel) throws -> LocalIdentity {
        if let existing = try load(label: label) {
            return existing
        }
        // A half-written identity (key without certificate or vice versa) is discarded.
        try delete(label: label)
        try create(label: label)
        guard let created = try load(label: label) else {
            throw IdentityError.keychain(operation: "load after create", status: errSecItemNotFound)
        }
        return created
    }

    /// Lowercase hex SHA-256 of the certificate DER (64 characters).
    public static func fingerprint(der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    public static func delete(label: String) throws {
        for itemClass in [kSecClassCertificate, kSecClassKey] {
            // The file-based keychain may delete one match per call; loop until none remain.
            while true {
                let query: [CFString: Any] = [kSecClass: itemClass, kSecAttrLabel: label]
                let status = SecItemDelete(query as CFDictionary)
                if status == errSecItemNotFound { break }
                guard status == errSecSuccess else {
                    throw IdentityError.keychain(operation: "delete", status: status)
                }
            }
        }
    }

    private static func load(label: String) throws -> LocalIdentity? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassCertificate,
            kSecAttrLabel: label,
            kSecReturnRef: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let result, CFGetTypeID(result) == SecCertificateGetTypeID() else {
            throw IdentityError.keychain(operation: "copy certificate", status: status)
        }
        let certificate = result as! SecCertificate
        var identity: SecIdentity?
        let identityStatus = SecIdentityCreateWithCertificate(nil, certificate, &identity)
        // A certificate without its private key is not a usable identity.
        if identityStatus == errSecItemNotFound { return nil }
        guard identityStatus == errSecSuccess, let identity else {
            throw IdentityError.keychain(operation: "create identity", status: identityStatus)
        }
        let der = SecCertificateCopyData(certificate) as Data
        return LocalIdentity(secIdentity: identity, certificateDER: der, fingerprint: fingerprint(der: der))
    }

    // The key is generated directly in the login keychain. Converting a CryptoKit
    // key and adding it with SecItemAdd fails with errSecMissingEntitlement (-34018)
    // for unsigned binaries such as the `swift test` runner.
    private static func create(label: String) throws {
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecPrivateKeyAttrs: [kSecAttrIsPermanent: true, kSecAttrLabel: label] as [CFString: Any],
        ]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            let reason = error.map { String(describing: $0.takeRetainedValue()) } ?? "unknown"
            throw IdentityError.keyCreation(reason)
        }
        let der = try selfSignedCertificateDER(privateKey: privateKey)
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else {
            throw IdentityError.keychain(operation: "create certificate", status: errSecDecode)
        }
        try add(
            [kSecClass: kSecClassCertificate, kSecValueRef: certificate, kSecAttrLabel: label],
            operation: "add certificate")
        // The login keychain ignores kSecAttrLabel on add and labels certificates
        // with their subject CN, so the label is set explicitly afterwards.
        let update = SecItemUpdate(
            [kSecClass: kSecClassCertificate, kSecValueRef: certificate] as CFDictionary,
            [kSecAttrLabel: label] as CFDictionary)
        guard update == errSecSuccess else {
            SecItemDelete([kSecClass: kSecClassCertificate, kSecValueRef: certificate] as CFDictionary)
            throw IdentityError.keychain(operation: "label certificate", status: update)
        }
    }

    private static func add(_ attributes: [CFString: Any], operation: String) throws {
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw IdentityError.keychain(operation: operation, status: status)
        }
    }

    static func selfSignedCertificateDER(privateKey: SecKey) throws -> Data {
        let signingKey = try Certificate.PrivateKey(privateKey)
        let name = try DistinguishedName { CommonName(commonName) }
        let now = Date()
        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: signingKey.publicKey,
            notValidBefore: now.addingTimeInterval(-60),
            notValidAfter: Calendar(identifier: .gregorian)
                .date(byAdding: .year, value: validityYears, to: now)!,
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
                Critical(KeyUsage(digitalSignature: true))
                try ExtendedKeyUsage([.serverAuth, .clientAuth])
            },
            issuerPrivateKey: signingKey)
        return Data(try certificate.serializeAsPEM().derBytes)
    }
}
