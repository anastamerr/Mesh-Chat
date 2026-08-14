# ADR 0002: Android Platform Baseline

**Status:** Proposed; pending physical-device inventory
**Date:** 2026-08-13

## Context

The mesh requires simultaneous BLE scanning, advertising, GATT central/peripheral behavior, runtime permissions, and eventually controlled background operation. Supporting very old Android versions would enlarge permission and lifecycle branching before the core network is proven.

## Decision

Use API 26 as the initial minimum Android version, compile and target API 36, and use runtime capability checks for every optional radio feature.

The first experiment uses:

- Foreground-only execution
- `BluetoothLeScanner` for discovery
- `BluetoothLeAdvertiser` for presence
- Both GATT client and server roles
- Explicit runtime permission handling
- No Companion Device Manager association
- No Android background service until foreground multi-hop passes

The build baseline is JDK 17. The intended Android build pairing is AGP 9.2.x with Gradle 9.4.1. Exact patch versions remain pinned and updated deliberately rather than through dynamic dependency ranges.

## Capability gate

A device can act as a full Phase 1 relay only if it supports:

- BLE
- BLE advertising
- BLE scanning
- Concurrent behavior sufficient for the experiment
- GATT server and client operations used by the protocol

Unsupported capabilities produce an explicit in-app diagnostic. They must not fail silently.

## Consequences

- The experiment avoids legacy permission paths below Android 8.
- Runtime behavior still varies by chipset and manufacturer and must be measured.
- API level alone never proves relay capability.
- A lower minimum SDK can be reconsidered only after the protocol succeeds and a concrete adoption need justifies the compatibility cost.

## References

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Android Gradle Plugin versions](https://developer.android.com/build/releases/about-agp)
