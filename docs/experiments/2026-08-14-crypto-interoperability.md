# Kotlin–Swift Cryptographic Interoperability Experiment

**Date:** 2026-08-14
**Result:** Pass for JVM, Android, and Swift

## Objective

Freeze the v0 private-message cryptographic format without adopting a library-specific wire representation, then prove that Google Tink and Apple CryptoKit can consume each other's output.

## Construction exercised

- RFC 9180 base-mode HPKE
- X25519 DHKEM with HKDF-SHA256
- HPKE HKDF-SHA256
- AES-256-GCM
- Raw 32-byte X25519 keys
- Raw 32-byte Ed25519 keys
- Domain-separated canonical key-schedule context and signed content
- Sender signature inside the HPKE plaintext
- Canonical bounded private-message envelope

## Evidence

The frozen fixture contains test-only raw keys, canonical metadata, signed content, an Ed25519 signature, and independently generated Tink and CryptoKit HPKE ciphertexts.

- Kotlin/Tink reconstructs the raw keys and opens both ciphertexts.
- Swift/CryptoKit reconstructs the same keys and opens both ciphertexts.
- Both implementations recover the canonical envelope and original plaintext.
- Both verify the embedded Ed25519 signature over the same canonical bytes.
- Ciphertext mutation is rejected by both implementations.
- JVM tests additionally reject wrong recipients, changed metadata/context, wrong senders, malformed envelopes, invalid signatures, oversized input, changed acknowledgements, and mismatched private/public key material.
- API 36 Android instrumentation opens both frozen ciphertexts, rejects mutation, and completes a fresh-key authenticated round trip.

The Swift verification command used on this workstation was:

```shell
CLANG_MODULE_CACHE_PATH=/private/tmp/dm-swift-module-cache \
swift -sdk /Library/Developer/CommandLineTools/SDKs/MacOSX15.4.sdk \
interop/swift/CryptoInteropV0.swift \
test-vectors/crypto-v0.properties
```

It reports:

```text
PASS Swift CryptoKit opened both HPKE vectors and verified the Ed25519 vector
```

The explicit SDK was necessary because the installed default command-line SDK and Swift compiler versions do not currently match. This is a workstation tooling issue, not a protocol result.

## Review findings incorporated

- Removed the superseded test-only HPKE and Ed25519 candidate suites.
- Removed duplicate simulator context, acknowledgement, key, and primitive helpers.
- Kept Tink in one module and prevented its types or serialization from entering protocol APIs.
- Bound sender identity, recipient encryption identity, immutable packet metadata, plaintext length, and plaintext.
- Excluded relay-mutated hop/copy budgets from end-to-end bindings.
- Applied constant-time sender-key comparison after decryption.
- Rejected oversized ciphertext before invoking Tink.
- Verified that mismatched raw private/public keys fail reconstruction.
- Avoided assuming deterministic signature bytes after observing valid CryptoKit signature randomization.

## Limits

- Android protects persisted raw identity material with the `mesh-crypto-android` Keystore adapter; the keys still enter the app process when Tink reconstructs its primitives.
- Static HPKE recipient keys do not provide forward secrecy after key compromise.
- Trusted identity bootstrap, rotation, revocation, and recovery remain separate protocol work.
- This is interoperability evidence, not an independent cryptographic audit.

Android runtime and protected-storage evidence is recorded in the [Android runtime experiment](2026-08-14-android-runtime.md).
