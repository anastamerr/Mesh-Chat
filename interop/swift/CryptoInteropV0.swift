import CryptoKit
import Foundation

private let suite = HPKE.Ciphersuite(
    kem: .Curve25519_HKDF_SHA256,
    kdf: .HKDF_SHA256,
    aead: .AES_GCM_256
)
private let recipientPrivateKey = try Curve25519.KeyAgreement.PrivateKey(
    rawRepresentation: Data(hex: "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
)
private let senderSigningPrivateKey = try Curve25519.Signing.PrivateKey(
    rawRepresentation: Data(hex: "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
)
private let messageId = Data(hex: "404142434445464748494a4b4c4d4e4f")
private let recipientToken = Data(hex: "303132333435363738393a3b3c3d3e3f")
private let createdAt: UInt64 = 1_700_000_000_000
private let expiresAt: UInt64 = 1_700_000_060_000
private let plaintext = Data("swift-tink-v0".utf8)

private func commonMetadata(packetType: UInt8) -> Data {
    var output = Data([0, packetType])
    output.append(messageId)
    output.append(recipientToken)
    output.appendBigEndian(createdAt)
    output.appendBigEndian(expiresAt)
    return output
}

private func privateMessageContext() -> Data {
    var output = Data("dm:private-message-context:v0|".utf8)
    output.append(commonMetadata(packetType: 1))
    return output
}

private func privateMessageSignature() -> Data {
    var output = Data("dm:private-message-signature:v0|".utf8)
    output.append(commonMetadata(packetType: 1))
    output.append(recipientPrivateKey.publicKey.rawRepresentation)
    output.append(senderSigningPrivateKey.publicKey.rawRepresentation)
    output.appendBigEndian(UInt32(plaintext.count))
    output.append(plaintext)
    return output
}

private func privateMessageEnvelope(signature: Data) -> Data {
    var output = Data([0])
    output.append(senderSigningPrivateKey.publicKey.rawRepresentation)
    output.append(signature)
    output.appendBigEndian(UInt32(plaintext.count))
    output.append(plaintext)
    return output
}

private func open(_ combinedCiphertext: Data, context: Data) throws -> Data {
    let encapsulatedKey = combinedCiphertext.prefix(32)
    let ciphertext = combinedCiphertext.dropFirst(32)
    var recipient = try HPKE.Recipient(
        privateKey: recipientPrivateKey,
        ciphersuite: suite,
        info: context,
        encapsulatedKey: encapsulatedKey
    )
    return try recipient.open(ciphertext)
}

private func validateEnvelope(_ candidate: Data) throws {
    guard candidate.count >= 101, candidate[0] == 0 else {
        throw InteropError.mismatch("decrypted envelope header")
    }
    let signingKey = Data(candidate[1..<33])
    let embeddedSignature = Data(candidate[33..<97])
    let length = candidate[97..<101].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    let embeddedPlaintext = Data(candidate.dropFirst(101))
    guard signingKey == senderSigningPrivateKey.publicKey.rawRepresentation,
          length == embeddedPlaintext.count,
          embeddedPlaintext == plaintext,
          senderSigningPrivateKey.publicKey.isValidSignature(embeddedSignature, for: signedContent)
    else {
        throw InteropError.mismatch("decrypted envelope authentication")
    }
}

private let context = privateMessageContext()
private let signedContent = privateMessageSignature()
private let signature = try senderSigningPrivateKey.signature(for: signedContent)
private let envelope = privateMessageEnvelope(signature: signature)

if CommandLine.arguments == [CommandLine.arguments[0], "--emit"] {
    var sender = try HPKE.Sender(
        recipientKey: recipientPrivateKey.publicKey,
        ciphersuite: suite,
        info: context
    )
    let ciphertext = try sender.seal(envelope)
    print("recipient_hpke_private=\(recipientPrivateKey.rawRepresentation.hex)")
    print("recipient_hpke_public=\(recipientPrivateKey.publicKey.rawRepresentation.hex)")
    print("sender_signing_private=\(senderSigningPrivateKey.rawRepresentation.hex)")
    print("sender_signing_public=\(senderSigningPrivateKey.publicKey.rawRepresentation.hex)")
    print("context=\(context.hex)")
    print("signed_content=\(signedContent.hex)")
    print("swift_signature=\(signature.hex)")
    print("swift_envelope=\(envelope.hex)")
    print("swift_ciphertext=\((sender.encapsulatedKey + ciphertext).hex)")
} else if CommandLine.arguments.count == 2 {
    let values = try readProperties(URL(fileURLWithPath: CommandLine.arguments[1]))
    try requireHex(values, "recipient_hpke_public", recipientPrivateKey.publicKey.rawRepresentation)
    try requireHex(values, "sender_signing_public", senderSigningPrivateKey.publicKey.rawRepresentation)
    try requireHex(values, "context", context)
    try requireHex(values, "signed_content", signedContent)
    guard let signatureValue = values["swift_signature"] else {
        throw InteropError.missing("swift_signature")
    }
    guard let envelopeValue = values["swift_envelope"] else {
        throw InteropError.missing("swift_envelope")
    }
    let frozenSignature = Data(hex: signatureValue)
    let frozenEnvelope = Data(hex: envelopeValue)
    guard privateMessageEnvelope(signature: frozenSignature) == frozenEnvelope else {
        throw InteropError.mismatch("envelope")
    }
    guard senderSigningPrivateKey.publicKey.isValidSignature(frozenSignature, for: signedContent) else {
        throw InteropError.mismatch("Swift rejected the frozen Ed25519 signature")
    }
    for name in ["swift_ciphertext", "tink_ciphertext"] {
        guard let value = values[name] else { throw InteropError.missing(name) }
        let ciphertext = Data(hex: value)
        let opened = try open(ciphertext, context: context)
        try validateEnvelope(opened)
        if name == "swift_ciphertext" && opened != frozenEnvelope {
            throw InteropError.mismatch(name)
        }
        var tampered = ciphertext
        tampered[tampered.count - 1] ^= 1
        var rejected = false
        do {
            _ = try open(tampered, context: context)
        } catch {
            rejected = true
        }
        guard rejected else { throw InteropError.mismatch("tampered \(name)") }
    }
    print("PASS Swift CryptoKit opened both HPKE vectors and verified the Ed25519 vector")
} else {
    throw InteropError.usage
}

private enum InteropError: Error {
    case usage
    case missing(String)
    case mismatch(String)
}

private func readProperties(_ url: URL) throws -> [String: String] {
    var values: [String: String] = [:]
    for rawLine in try String(contentsOf: url, encoding: .utf8).split(separator: "\n") {
        let line = rawLine.trimmingCharacters(in: .whitespaces)
        if line.isEmpty || line.hasPrefix("#") { continue }
        let parts = line.split(separator: "=", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { throw InteropError.mismatch(line) }
        values[parts[0]] = parts[1]
    }
    return values
}

private func requireHex(_ values: [String: String], _ name: String, _ expected: Data) throws {
    guard let value = values[name] else { throw InteropError.missing(name) }
    guard Data(hex: value) == expected else { throw InteropError.mismatch(name) }
}

private extension Data {
    init(hex: String) {
        precondition(hex.count.isMultiple(of: 2))
        self.init(stride(from: 0, to: hex.count, by: 2).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            let end = hex.index(start, offsetBy: 2)
            return UInt8(hex[start..<end], radix: 16)!
        })
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }

    mutating func appendBigEndian<T: FixedWidthInteger>(_ value: T) {
        var encoded = value.bigEndian
        Swift.withUnsafeBytes(of: &encoded) { append(contentsOf: $0) }
    }
}
