# ADR 0003: Asynchronous Message Cryptography

**Status:** Proposed; acceptance requires interoperability tests
**Date:** 2026-08-13

## Context

Store-carry-forward delivery must encrypt a message when the recipient is not connected. Relays need to retain and forward opaque ciphertext without participating in an end-to-end interactive session.

The construction must be standardized, maintained on Android, reproducible on iOS, and separated from packet routing.

## Proposed decision

Use RFC 9180 HPKE for the Phase 1 sealed-message primitive with:

- KEM: X25519 with HKDF-SHA256
- KDF: HKDF-SHA256
- AEAD: AES-256-GCM

Use sender signatures for authentication, with the signed content placed inside the HPKE plaintext and domain-separated from every other signature use. Ed25519 is the initial signature candidate.

On Android, evaluate Google Tink 1.23.x behind an internal `MessageCrypto` interface. The routing and wire modules must not expose Tink keyset or primitive types.

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

If Tink adds an incompatible prefix or key representation, the adapter must use documented raw-key behavior or the choice must be reconsidered. Tink-specific wire formats must not become the protocol accidentally.

## References

- [RFC 9180: HPKE](https://www.rfc-editor.org/rfc/rfc9180.html)
- [Tink hybrid encryption](https://developers.google.com/tink/hybrid)
- [Tink digital signatures](https://developers.google.com/tink/digital-signature)
- [Apple CryptoKit HPKE](https://developer.apple.com/documentation/cryptokit/hpke)
