# Android Runtime and BLE Probe Experiment

**Date:** 2026-08-14
**Result:** Pass for Android cryptography and protected storage; partial BLE emulator evidence; physical radio gate pending

## Environment

- Apple silicon host
- Eclipse Temurin JDK 17.0.20+8
- Gradle 9.4.1
- Android Gradle Plugin 9.2.0 with built-in Kotlin
- Android SDK Platform 36 and Build Tools 36.0.0
- Platform Tools 37.0.1
- Android Emulator 37.1.11
- API 36 ARM64 AOSP Automated Test Device image

Android 17/API 37 remains a preview platform in the installed SDK channel. The probe deliberately compiles and targets stable API 36; strict lint suppresses only the two checks whose sole finding is the newer preview.

## Android cryptography result

`AndroidCryptoTest` ran inside an API 36 Android process:

- opened the frozen Swift/CryptoKit HPKE ciphertext;
- opened the frozen Kotlin/Tink HPKE ciphertext;
- matched the canonical associated context;
- rejected a modified ciphertext; and
- generated fresh keys and completed an authenticated seal/open round trip.

Result: 2 tests, 0 failures, 0 errors. The JVM, Android, and Swift checks consume the single fixture at `test-vectors/crypto-v0.properties`.

## Protected identity result

`AndroidIdentityStore` uses a non-exportable Android Keystore AES-256 key restricted to GCM encryption/decryption. It encrypts the fixed raw X25519/Ed25519 material with random provider-generated IVs, format-specific associated data, and an atomic file in the app's no-backup directory.

The API 36 instrumentation suite proved:

- the same identity loads across store instances;
- neither raw private key occurs in the stored ciphertext;
- the Keystore master key has no exportable encoding;
- modified storage fails GCM authentication; and
- deleting the master key does not silently replace the existing identity.

Result: 3 tests, 0 failures, 0 errors. The final adapter AAR is 12 KiB and adds no runtime library beyond the existing `mesh-crypto` dependency.

## BLE probe result

The 908 KiB debug probe APK uses only platform UI and Bluetooth APIs. It requests the Android 12+ scan, advertise, and connect permissions; keeps legacy Bluetooth/location permissions capped at API 30; and does not declare `neverForLocation`, which can filter some advertisements.

One run concurrently:

- publishes a connectable service advertisement;
- starts a filtered low-latency scan;
- publishes a writable GATT characteristic;
- connects to one matching peer;
- discovers that peer's service;
- requests ATT MTU 517 and records the negotiated value; and
- performs a response-confirmed GATT write and records receipt on the server.

Strict Android lint reports no issues. The APK has no AndroidX, Compose, coroutine, dependency-injection, or networking runtime dependency.

## Two-emulator BLE netsim observation

Two independent API 36 AVDs ran concurrently with distinct emulated Bluetooth addresses and BLE RSSI fixed at -65 dBm. Both reported BLE, scanner, advertiser, multiple advertising, GATT-server publication, and accepted concurrent scan/advertise operations. Each reported maximum advertising data of 512 bytes and no operation failure.

Neither AVD discovered the other during the overlapping run, so no GATT client, MTU, or write result was possible. Distinct addresses rule out the known same-address collision, but the cause was not established. This is recorded as partial emulator evidence, not a product failure and not a physical-radio pass.

Official emulator documentation confirms that netsim is a simulated radio environment. It cannot establish chipset behavior, antenna range, interference, power cost, or real-device concurrency, so it does not replace the physical matrix.

## Commands

```shell
./gradlew :android-probe:testDebugUnitTest :android-probe:lintDebug :android-probe:assembleDebug
./gradlew :android-probe:connectedDebugAndroidTest
./gradlew :mesh-crypto-android:lintDebug :mesh-crypto-android:connectedDebugAndroidTest
```

## Remaining limit

No physical phone was attached to adb during this experiment. The exact three-phone procedure and unfilled evidence table are in [Phase 0 physical-device matrix](../11-phase-0-device-matrix.md).
