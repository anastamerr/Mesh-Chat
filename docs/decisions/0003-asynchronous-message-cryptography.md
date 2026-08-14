# ADR 0003: Asynchronous Message Cryptography

**Status:** Accepted for Phase 1
**Date:** 2026-08-13

## Context

Store-carry-forward delivery must encrypt a message when the recipient is not connected. Relays need to retain and forward opaque ciphertext without participating in an end-to-end interactive session.

The construction must be standardized, maintained on Android, reproducible on iOS, and separated from packet routing.

## Decision

Use RFC 9180 HPKE for the Phase 1 sealed-message primitive with:

- KEM: X25519 with HKDF-SHA256
- KDF: HKDF-SHA256
- AEAD: AES-256-GCM

Use sender signatures for authentication, with the signed content placed inside the HPKE plaintext and domain-separated from every other signature use. Ed25519 is the initial signature candidate.

On Android, use Google Tink 1.23.x behind the project-owned `MessageCrypto` interface. The routing and wire modules do not expose Tink keyset or primitive types. Cross-platform identities use raw 32-byte X25519 encryption keys and raw 32-byte Ed25519 signing keys; Tink serialization is local implementation detail only.

## Security properties and limitations

- HPKE provides recipient confidentiality and ciphertext integrity.
- Base-mode HPKE alone does not authenticate the sender.
- Sender authentication must bind the message ID, recipient identity, timestamps, payload type, and payload.
- Relay-visible headers must be passed as authenticated context where appropriate.
- Static recipient-key compromise can expose retained sealed messages; Phase 1 does not claim forward secrecy for offline mail.
- Live forward-secret sessions are a separate future decision and may use Noise or another reviewed protocol.

## Acceptance tests

This decision becomes accepted only when tests prove:

1. Android encrypt/decrypt round trip
2. Wrong-recipient rejection
3. Ciphertext modification rejection
4. Associated-context modification rejection
5. Sender-signature verification and forgery rejection
6. Stable public-key serialization
7. Frozen golden vectors
8. Swift/CryptoKit interoperability using the same RFC 9180 suite and wire representation

Items 1–8 pass. The frozen bidirectional CryptoKit/Tink ciphertexts and Ed25519 verification pass on the JVM and inside an API 36 Android process; Android also generates fresh keys and completes an authenticated encrypt/decrypt round trip. The workstation Swift toolchain remains the independent interoperability implementation.

Android stores the raw cross-platform identity through `mesh-crypto-android`: a non-exportable Android Keystore AES-256-GCM master key encrypts an atomic no-backup identity file. Tampering and loss of the master key fail closed. Hardware backing is reported rather than assumed, and user authentication is not required because opportunistic messaging must operate while the foreground mesh is active without a prompt per message.

If Tink adds an incompatible prefix or key representation, the adapter must use documented raw-key behavior or the choice must be reconsidered. Tink-specific wire formats must not become the protocol accidentally.

## References

- [RFC 9180: HPKE](https://www.rfc-editor.org/rfc/rfc9180.html)
- [Tink hybrid encryption](https://developers.google.com/tink/hybrid)
- [Tink digital signatures](https://developers.google.com/tink/digital-signature)
- [Apple CryptoKit HPKE](https://developer.apple.com/documentation/cryptokit/hpke)
