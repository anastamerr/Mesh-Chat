# Phase 0 Physical-Device Matrix

**Status:** Awaiting three physical Android devices
**Last updated:** 2026-08-14

This is the final Phase 0 hardware gate. Emulator results may validate Android API behavior, but only physical phones can establish radio range, chipset concurrency, negotiated MTU, and repeatable A–B–C placement.

## Exit rule

Phase 0 exits only when all three devices:

- run the probe with Nearby Devices permission granted and Bluetooth enabled;
- start scanning and connectable advertising concurrently;
- publish the GATT service;
- discover and connect to a second physical probe device;
- discover the probe characteristic, negotiate an ATT MTU, and complete a confirmed GATT write in both client and server roles;
- record their Android Keystore master-key protection level; and
- support a documented placement where A–B and B–C work while A–C does not form a session.

## Inventory

| Label | Manufacturer and model | Android / API | Security patch | BLE chipset or SoC | Keystore protection | Result |
|---|---|---:|---|---|---|---|
| A | Pending | Pending | Pending | Pending | Pending | Not run |
| B | Pending | Pending | Pending | Pending | Pending | Not run |
| C | Pending | Pending | Pending | Pending | Pending | Not run |

## Capability observations

Record the exact report emitted by `MeshPhase0Probe`; do not infer support from API level.

| Device | Scan | Advertise | GATT server | Peer found | GATT client | Service | MTU | Client write | Server write | Failures |
|---|---|---|---|---|---|---|---:|---|---|---|
| A | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending |
| B | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending |
| C | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending | Pending |

Also retain the static capability fields for multiple advertising, offloaded filtering/batching, 2M and coded PHY, extended/periodic advertising, and maximum advertising-data length.

## Repeatable procedure

1. Connect the three phones by USB, unlock them, enable developer options and USB debugging, and run `adb devices -l`. Every serial must show `device`, not `unauthorized` or `offline`.
2. For each serial, record `ro.product.manufacturer`, `ro.product.model`, `ro.build.version.release`, `ro.build.version.sdk`, and `ro.build.version.security_patch` with `adb -s SERIAL shell getprop PROPERTY`.
3. Build the probe with `./gradlew :android-probe:assembleDebug` and install `android-probe/build/outputs/apk/debug/android-probe-debug.apk` on each phone.
4. Put all phones in airplane mode, then re-enable Bluetooth only. Confirm Wi-Fi, cellular data, VPNs, and USB tethering are off.
5. Launch the probe on two phones, clear logcat, and start both 12-second runs within five seconds. Accept the Nearby Devices prompt if shown.
6. Capture each result with `adb -s SERIAL logcat -d -s MeshPhase0Probe:I '*:S'`. A valid pairwise pass contains scan, advertising, GATT-server, peer, client, service, MTU, and both write observations.
7. Repeat three times for A–B, B–C, and A–C, reversing which phone starts first on the second run. Record intermittent failures rather than selecting only a successful run.
8. Run `./gradlew :mesh-crypto-android:connectedDebugAndroidTest` on the connected phones. Retain the JUnit result and the `MeshPhase0Identity` protection report for each device.
9. Measure pairwise discovery and connection at increasing separation in the intended test location. Choose fixed, marked A/B/C positions where A–B and B–C pass repeatedly and A–C has no discovery or connection during at least three equivalent observation windows.
10. During the later relay experiment, treat the topology as valid only when diagnostics show no A–C session while the matching opaque packet travels through B. Distance, a software routing rule, or disabled application UI alone is not proof.

If ordinary phone range makes step 9 impossible, use a larger site or purpose-built RF attenuation equipment and document its placement. Do not freeze a fragmentation size until physical MTU results are recorded; the initial frame payload must fit the smallest observed link after protocol overhead.

## Current blocker

On 2026-08-14, `adb devices -l` reported no attached physical device. The repository and workstation are ready to run this procedure, but the table cannot be completed honestly without the three phones.
