# Phase 0 Findings

**Status:** In progress
**Last updated:** 2026-08-14

## Local toolchain audit

The development machine currently has:

- Apple silicon (`arm64`)
- macOS 26.5.1
- Apple command-line developer tools

The normal shell does not currently expose:

- A permanently installed Java runtime or JDK
- A permanently installed Gradle distribution
- Kotlin compiler
- Android SDK
- `adb`
- Android Studio

For Phase 0, checksum-verified temporary distributions of Eclipse Temurin JDK 17.0.20+8 and Gradle 9.4.1 were used. The Gradle wrapper is now committed with distribution URL validation and the official distribution checksum. This makes the JVM build definition reproducible without pretending the permanent Android workstation setup is complete.

Android compilation and physical-device tests still require the Android SDK, platform tools, and real phones.

## Android BLE findings

Official Android guidance supports the following decisions:

- BLE scanning and advertising are available from API 21, but advertising support remains a runtime hardware capability and must be checked through `isMultipleAdvertisementSupported()`.
- Android 12/API 31 introduced runtime `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT` permissions.
- When scan results are not used to derive location, `BLUETOOTH_SCAN` can declare `neverForLocation`; this can filter some beacons and must be validated against our service advertisement.
- A process must remain alive for ordinary callback-based scanning. A filtered `PendingIntent` scan can wake a stopped process in later background work.
- Long-lived connections may use a `connectedDevice` foreground service, subject to modern foreground-service restrictions.
- Companion-device APIs are oriented toward explicit associations with peripherals and are not the initial choice for an ad-hoc phone mesh.

Primary references:

- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [BLE background communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [BluetoothAdapter capabilities](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)
- [BluetoothLeAdvertiser limits](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser.html)

## Platform baseline

The proposed Android baseline is:

- `minSdk = 26`
- `compileSdk = 36`
- `targetSdk = 36`
- JDK 17
- Kotlin 2.3.21
- Android Gradle Plugin 9.2.x when the Android app module is added
- Gradle 9.4.1 for AGP 9.2 compatibility

The pure protocol module uses no Android API. The Android adapter will detect BLE advertising, scanning, MTU, PHY, and Wi-Fi capabilities at runtime rather than inferring them from OS version.

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

Test-scoped Tink 1.23.0 experiments now pass for HPKE round trip, wrong-recipient rejection, ciphertext tampering, and authenticated-context tampering. Ed25519 candidate tests also pass for valid verification and rejection of changed content, changed signatures, and wrong-sender keys. This is evidence for the primitive choices, not acceptance of the cryptographic protocol. Canonical signed-content encoding, raw key serialization, frozen cross-platform vectors, and Swift interoperability remain unresolved.

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
- Four HPKE-candidate behavior tests
- Four Ed25519 sender-signature candidate tests
- Eleven durable engine/storage tests
- Five deterministic A–B–C simulator tests
- Zero skipped tests, failures, or errors

The current clean run contains 29 tests. Detailed records are in the [Phase 0 JVM experiment report](experiments/2026-08-13-phase-0-jvm.md), [three-node simulator experiment](experiments/2026-08-14-three-node-simulator.md), and [durable store-and-forward experiment](experiments/2026-08-14-durable-store-forward.md).

## Remaining Phase 0 gates

- Establish the permanent JDK 17 workstation setup.
- Install the Android SDK and platform tools.
- Inventory at least three physical Android test devices.
- Run a BLE capability probe on each device.
- Define canonical signed-content encoding and a production crypto adapter boundary.
- Freeze cryptographic and packet golden vectors.
- Record observed advertisement, connection, and payload limits.
