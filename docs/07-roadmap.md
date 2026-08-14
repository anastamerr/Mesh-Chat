# Roadmap

**Status:** Working plan
**Last updated:** 2026-08-14

The roadmap is evidence-driven. A phase advances only when its exit criteria are met on physical devices.

## Phase 0: Research and specification

- Confirm Android BLE capabilities across available test devices.
- Select minimum Android version.
- Select maintained cryptographic dependencies and protocol construction.
- Freeze the first packet encoding and test vectors.
- Create the Kotlin project and automated test foundation.
- Prove the A–B–C flow in a deterministic byte-level simulator.
- Prove bounded durable store-and-forward across node reconstruction.

**Exit:** The pre-implementation gates in the Phase 1 specification are resolved.

## Phase 1: Direct and three-phone relay

- Native Kotlin Android engine
- Foreground discovery and connections
- Direct private text delivery
- Three-phone controlled forwarding
- Persistent encrypted queues
- Deduplication and authenticated acknowledgements
- Real-device experiment report

**Exit:** The Phase 1 definition of done and experiment plan pass.

## Phase 2: Store-carry-forward reliability

- Disconnected recipient delivery
- Bounded redundant copies
- Relay restart recovery
- Better retry and eviction policy
- Congestion and resource limits
- Longer movement-based experiments

**Exit:** Messages survive planned partitions and later encounters with measured reliability.

## Phase 3: Adaptive routing and privacy

- Encounter-history-based relay scoring
- Multipath policy by message priority
- Rotating radio identifiers
- Private contact recognition
- Metadata-minimizing discovery
- Abuse controls and parser fuzzing

**Exit:** Adaptive behavior improves measured delivery or resource cost over the Phase 2 baseline.

## Phase 4: Expo application integration

- Local Expo native module
- Shared TypeScript API
- Expo development builds
- Conversations and private-circle UI
- QR onboarding
- Honest delivery-state interface
- Check-ins and encrypted meeting locations

**Exit:** Nontechnical testers can complete a group coordination scenario without transport knowledge.

## Phase 5: Android background operation

- Foreground service and user-visible relay controls
- Battery modes
- Restart and OS-kill recovery
- Notification behavior
- Extended battery and reliability trials

**Exit:** Background behavior and its limitations are measured across the supported Android device matrix.

## Phase 6: iOS native engine

- Swift implementation of the shared protocol
- Core Bluetooth discovery and data transport
- State preservation and restoration
- Cross-platform golden tests
- Android-to-iPhone relay experiments

**Exit:** Android and iOS exchange private messages and acknowledgements under the same protocol, with platform limitations documented.

## Phase 7: Hybrid transports

- Higher-bandwidth local Wi-Fi where supported
- Optional decentralized internet gateway
- Gateway transport carries only encrypted envelopes
- Automatic route selection and fallback
- Large-payload policy separated from text delivery

**Exit:** A message can change transport without changing application semantics or user action.

## Phase 8: Community relays and field trials

- Independently operated relay mode for old phones, laptops, or small nodes
- Organizer deployment tools
- Controlled university or event pilot
- Reliability, privacy, battery, congestion, and adoption measurements
- External security review before safety-sensitive positioning

**Exit:** A real group continues using the product during a connectivity disruption and the results justify broader deployment.

## Deferred features

- Voice and video calls
- Large media courier delivery
- Public anonymous channels
- Global username directory
- Phone-number-based discovery
- Emergency-service integration
- Economic incentives or relay payments

Deferred does not mean rejected; it means these features cannot delay proof of the decentralized messaging core.
