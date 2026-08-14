# ADR 0001: Begin with an Android-First Native Mesh Engine

**Status:** Accepted
**Date:** 2026-08-13

## Context

The long-term application is intended to use an Expo/React Native interface on Android and iOS. The network must discover peers, manage BLE connections, route packets, persist queues, protect keys, and later operate under platform-specific background constraints.

Beginning simultaneously with React Native, Android, and iOS would mix radio uncertainty with cross-platform module integration and UI state management. Failure causes would be difficult to isolate.

## Decision

Build the first mesh proof of concept as a minimal native Android application in Kotlin.

The Kotlin networking code must be organized as an engine with a small public API rather than embedded in Android activities or composables. After the three-phone experiment succeeds, move or expose the engine through a local Expo module and build the shared React Native interface.

The iOS counterpart will be implemented in Swift against the same written protocol, packet fixtures, and cryptographic test vectors.

## Consequences

### Positive

- Direct access to Android Bluetooth and service lifecycle APIs
- Easier radio debugging and instrumentation
- Fewer layers during the riskiest experiment
- A clean native engine boundary for future Expo integration
- Evidence collected before duplicating work on iOS

### Costs

- A small prototype interface will be temporary.
- Kotlin and Swift platform implementations will contain some duplication.
- Cross-platform UI work starts later.
- Protocol compatibility must be enforced through specifications and tests.

## Expo decision

Expo remains the planned application framework, but the project will use custom development builds rather than Expo Go. The native mesh engine will expose high-level operations and events; JavaScript will not handle individual radio packets or own persistent delivery state.

## Revisit conditions

Reconsider the implementation boundary if:

- Maintaining equivalent Kotlin and Swift protocol logic becomes a demonstrated source of defects.
- A shared Rust or C++ core materially reduces duplication without compromising platform lifecycle control.
- Real-device experiments show that the selected transport architecture is infeasible.

Any shared core would still leave Bluetooth, Wi-Fi, permissions, services, and background lifecycle in platform-native adapters.
