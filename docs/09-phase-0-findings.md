# Phase 0 Findings

**Status:** Software-complete; physical-device evidence pending
**Last updated:** 2026-08-14

## Local toolchain audit

The development machine now has:

- Apple silicon (`arm64`)
- macOS 26.5.1
- Apple command-line developer tools
- Eclipse Temurin JDK 17.0.20+8 installed in the standard per-user Java directory
- Pinned Gradle wrapper 9.4.1 with distribution URL validation and checksum
- Android command-line tools 22.0, installed from Google's ARM64 archive after matching its published SHA-256
- Android SDK Platform 36 revision 2 and Build Tools 36.0.0
- Platform Tools and adb 37.0.1
- Android Emulator 37.1.11 and the API 36 ARM64 AOSP Automated Test Device image

The permanent toolchain runs the clean JVM suite, Android compilation, strict lint, APK/AAR packaging, and connected instrumentation tests. Android Studio is optional for this command-line experiment. Real phones remain necessary for radio evidence.

## Android BLE findings

Official Android guidance supports the following decisions:

- BLE scanning and advertising are available from API 21, but advertising support remains a runtime hardware capability and must be checked through `isMultipleAdvertisementSupported()`.
- Android 12/API 31 introduced runtime `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT` permissions.
- `BLUETOOTH_SCAN` can declare `neverForLocation`, but Android warns that doing so filters some beacons. The Phase 0 capability probe deliberately avoids the flag until the discovery design has physical evidence.
- A process must remain alive for ordinary callback-based scanning. A filtered `PendingIntent` scan can wake a stopped process in later background work.
- Long-lived connections may use a `connectedDevice` foreground service, subject to modern foreground-service restrictions.
- Companion-device APIs are oriented toward explicit associations with peripherals and are not the initial choice for an ad-hoc phone mesh.

Primary references:

- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [BLE background communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [BluetoothAdapter capabilities](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)
- [BluetoothLeAdvertiser limits](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser.html)

The `android-probe` app now performs the runtime gate directly. It reports static adapter features, concurrently scans and advertises a private probe service, publishes a writable GATT characteristic, discovers and connects to one peer, requests ATT MTU 517, performs a response-confirmed write, and records receipt on the peer server. Unsupported hardware remains installable because Bluetooth features are optional in the manifest and failures appear in the report.

Two API 36 emulators accepted concurrent scanning, advertising, and GATT-server publication with no API failure, but did not discover each other through BLE netsim. This is useful Android-stack evidence only; it does not pass the physical radio gate.

## Platform baseline

The proposed Android baseline is:

- `minSdk = 26`
- `compileSdk = 36`
- `targetSdk = 36`
- JDK 17
- Kotlin 2.3.21
- Android Gradle Plugin 9.2.0 with built-in Kotlin
- Gradle 9.4.1 for AGP 9.2 compatibility

The pure protocol module uses no Android API. The Android probe detects BLE advertising, scanning, MTU, PHY, and GATT capabilities at runtime rather than inferring them from OS version. API 37 remains a preview, so strict lint suppresses only the checks whose entire finding is that the preview exists.

## Cryptography findings

HPKE from RFC 9180 is the leading Phase 1 asynchronous-encryption choice:

- Google Tink recommends HPKE using X25519, HKDF-SHA256, and AES-256-GCM for hybrid encryption.
- Tink Android is fully supported from API 24, below the proposed API 26 minimum.
- Apple CryptoKit exposes RFC 9180 HPKE, providing a plausible future Swift interoperability path.
- Base HPKE protects confidentiality and ciphertext integrity but does not authenticate the sender. A separate sender signature or authenticated HPKE mode is required.
- Static-recipient HPKE does not provide forward secrecy after recipient-key compromise for retained ciphertext. A live-session protocol such as Noise remains a later design option.

Primary references:

- [RFC 9180](https://www.rfc-editor.org/rfc/rfc9180.html)
- [Tink hybrid encryption](https://developers.google.com/tink/hybrid)
- [Tink Java/Android setup](https://developers.google.com/tink/setup/java)
- [Tink digital signatures](https://developers.google.com/tink/digital-signature)
- [Apple CryptoKit HPKE](https://developer.apple.com/documentation/cryptokit/hpke)

The `mesh-crypto` module now implements the library-neutral `MessageCrypto` boundary with Tink 1.23.0 behind it. Canonical domain-separated context, signed-content, and encrypted-envelope formats are dependency-free in `mesh-protocol`. Fixed 32-byte raw X25519 and Ed25519 keys reconstruct successfully, while mismatched pairs fail closed. Frozen ciphertexts pass bidirectionally: Tink opens the CryptoKit vector and CryptoKit opens the Tink vector. Wrong recipients, modified ciphertext/context, wrong senders, invalid signatures, malformed envelopes, oversized input, and modified acknowledgements are rejected.

The same fixture now passes inside an API 36 Android process, together with fresh Android key generation and authenticated seal/open. The `mesh-crypto-android` adapter encrypts raw identity material in an atomic no-backup file using a non-exportable Android Keystore AES-256-GCM master key. Its device tests prove persistence, absence of raw private keys on disk, non-exportability, tamper rejection, and fail-closed behavior when the master key is missing. The cryptography decision is accepted for Phase 1, subject to its documented lack of post-compromise forward secrecy.

## Protocol work completed

The `mesh-protocol` module defines:

- Immutable 128-bit message IDs and routing tokens
- Two v0 routed packet types: private message and delivery acknowledgement
- A fixed 60-byte header
- Big-endian canonical encoding
- A 16 KiB hard payload limit
- Strict decoding with typed failure reasons
- Defensive copying of mutable byte arrays
- A frozen golden packet vector
- Boundary, timestamp, mutation, and round-trip tests

The protocol core has no Android, BLE, coroutine, serialization, database, or cryptography dependency.

## Three-node simulation completed

The `mesh-simulator` module now proves the first required topology without radio hardware:

- A and C have no direct link.
- A private-message packet travels A → B → C as encoded bytes.
- The payload is sealed for C using the proposed HPKE suite.
- B receives no user-visible message and its own recipient key cannot decrypt the payload.
- C decrypts the original plaintext.
- C's Ed25519-authenticated acknowledgement travels C → B → A.
- An acknowledgement reuses the original message ID safely because deduplication keys include packet type.
- Retried packets are not displayed twice.
- An exhausted copy does not poison deduplication state and block a later viable copy.
- Hop and copy budgets are decremented at the relay.

The simulator has no Android or BLE dependency and records metadata-only transmission evidence. Its deliberately narrow forwarding policy chooses one eligible next hop, which is sufficient for the required linear topology. Multipath routing remains deferred until measured traces justify it.

## Durable store-and-forward completed

The `mesh-engine` module now owns the production-facing JVM proof boundary:

- Outbound and relay packets are persisted before they are offered to a link.
- Storage is bounded by both item count and total canonical bytes.
- Packet files are forced and atomically moved into visibility.
- Malformed persisted state, filename/content mismatches, symbolic links, and same-ID content conflicts fail closed.
- Expired queue entries are removed persistently.
- Relay ciphertext is recovered byte-for-byte after node reconstruction.
- Recipient delivery state survives reconstruction and suppresses later duplicates.
- A verified receipt is persisted before its private outbox entry is removed.
- Invalid or unsolicited acknowledgements cannot create delivered state.
- A disconnected relay queues and later delivers 100 separately encrypted messages exactly once.

The directory adapter has one process owner and proves ordinary process-restart recovery. It does not claim protection from storage hardware failure or concurrent multi-process access to one node directory. Android will later implement the same `PacketStore` contract with platform storage.

## JVM verification completed

A clean, offline JVM build passes with:

- Kotlin explicit API mode
- Compiler warnings treated as errors
- Five canonical packet codec tests
- Eight canonical cryptographic binding/envelope tests
- Eight production crypto-boundary and interoperability tests
- Eleven durable engine/storage tests
- Five deterministic A–B–C simulator tests
- One process-isolated A–B–C recovery test
- Zero skipped tests, failures, or errors

The current clean run contains 38 tests. Detailed records are in the [Phase 0 JVM experiment report](experiments/2026-08-13-phase-0-jvm.md), [three-node simulator experiment](experiments/2026-08-14-three-node-simulator.md), [durable store-and-forward experiment](experiments/2026-08-14-durable-store-forward.md), [multi-process loopback experiment](experiments/2026-08-14-multi-process-loopback.md), and [Kotlin–Swift interoperability experiment](experiments/2026-08-14-crypto-interoperability.md).

## Android verification completed

- One local Android report-format test passes.
- Two API 36 cryptographic instrumentation tests pass.
- Three API 36 Android Keystore identity tests pass.
- Strict lint reports zero issues for both Android modules.
- The probe debug APK is 908 KiB and has no AndroidX or other application runtime dependency.
- The protected identity adapter AAR is 12 KiB and depends only on the existing crypto module.

Full details and evidence limits are in the [Android runtime and BLE probe experiment](experiments/2026-08-14-android-runtime.md).

## Remaining Phase 0 gate

Only physical radio evidence remains:

- Inventory at least three physical Android phones.
- Run the probe pairwise and record advertisement, connection, MTU, GATT-write, and Keystore results.
- Establish fixed A/B/C positions where A–B and B–C work repeatedly while A–C does not form a session.

No phone was attached to adb on 2026-08-14. The exact procedure and unfilled evidence table are in the [Phase 0 physical-device matrix](11-phase-0-device-matrix.md). Phase 0 must not be marked complete until that table contains real measurements.
