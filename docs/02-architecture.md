# System Architecture

**Status:** Proposed
**Last updated:** 2026-08-14

## Architectural goals

- Operate locally without internet or a company backend.
- Support changing and intermittent connectivity.
- Keep message contents confidential from relays.
- Permit multiple transport implementations.
- Survive application restarts and temporary partitions.
- Expose a small, stable interface to the future React Native application.
- Make delivery behavior observable and testable.

## Layered model

```text
Application UI
  conversations, circles, composer, delivery state
        |
Application service
  user actions, local message timeline, notifications
        |
Messaging engine
  identity, encryption, envelopes, acknowledgements
        |
Delivery engine
  outbox, deduplication, retry, relay policy, expiration
        |
Routing engine
  direct path, controlled forwarding, future path scoring
        |
Transport abstraction
  BLE | local Wi-Fi | community relay | internet gateway
        |
Platform adapters
  Android Kotlin | iOS Swift
```

Higher layers must not depend on BLE-specific concepts such as GATT characteristics or scan callbacks.

## JVM proof harness

The `mesh-engine` module owns transport-independent node behavior and a narrow `PacketStore` boundary. Its JVM `DirectoryPacketStore` writes canonical packet bytes through a forced temporary file and atomic rename, rejects corrupt state, and applies one hard item/byte limit across queues, deliveries, and receipts. Receipt persistence precedes outbox removal so reconstruction resolves an interrupted confirmation safely.

The `mesh-crypto` module owns the end-to-end cryptography boundary. Its public API exposes project-owned identities, metadata, and typed open results; no Tink keyset, primitive, or serialization type crosses the boundary. Public and private keys use fixed 32-byte raw representations.

The `mesh-crypto-android` adapter protects that raw material at rest with a non-exportable Android Keystore AES-256-GCM key and an atomic no-backup file. It reports whether the master key is software-, TEE-, or StrongBox-backed without requiring StrongBox. Raw identity keys necessarily enter the native process when Tink reconstructs HPKE and Ed25519 primitives; the claim is protected storage at rest, not hardware-isolated message signing.

The `mesh-simulator` module supplies deterministic, explicit links between isolated `MeshNode` instances. Every virtual transmission crosses a link as bytes encoded and decoded by `mesh-protocol`; nodes do not share packet objects, stores, or routing state.

Its test source set also contains a deliberately small loopback harness. It launches A, B, and C as separate JVM processes, moves canonical packet bytes over TCP, and forcibly terminates B before reconstructing it from the same store. This harness is process-isolation evidence only: it is not a production transport and is excluded from the module's runtime artifact.

The simulator intentionally implements only the single-copy forwarding policy needed for the A–B–C proof. Its encrypted scenarios now consume `mesh-crypto` rather than assembling test-only primitives. It is evidence for packet, relay, persistent deduplication, encryption, authenticated acknowledgement, store-carry-forward, and ordinary process-restart behavior—not evidence for BLE discovery, range, background execution, or battery use.

## Android proof-of-concept components

### `IdentityStore`

- Creates and loads the device identity.
- Stores private key material using platform-protected storage.
- Exposes public identity and signing operations.
- Does not expose raw private keys to the UI.

### `PeerDiscovery`

- Advertises the protocol service.
- Scans for compatible peers.
- Resolves temporary transport identifiers to active peer sessions.
- Reports peer appearance and disappearance.

### `PeerConnection`

- Establishes a bidirectional BLE data channel.
- Negotiates protocol version and capabilities.
- Frames, fragments, transmits, receives, and reassembles packets.
- Applies bounded queues and backpressure.

### `MessageRouter`

- Attempts direct delivery first.
- Forwards eligible packets according to TTL and copy budget.
- Never interprets private message plaintext when acting only as a relay.
- Emits routing decisions as local diagnostic events.

### `MessageStore`

- Persists the encrypted outbox and relay queue.
- Tracks message lifecycle and acknowledgement state.
- Maintains a bounded deduplication index.
- Removes expired and acknowledged entries.

### `CryptoEngine`

- Uses maintained, reviewed cryptographic libraries.
- Establishes authenticated sender-recipient encryption.
- Signs or authenticates delivery acknowledgements.
- Keeps cryptographic choices behind an internal interface.

### `MeshService`

- Coordinates discovery, connections, routing, and persistence.
- Owns the Android service lifecycle in later phases.
- Continues operating independently of the UI.
- Exposes high-level commands and events.

### `Diagnostics`

- Records local counters and structured events without message plaintext.
- Measures discovery, latency, retries, duplicates, queue depth, and failures.
- Supports export only through an explicit development action.

## Future Expo boundary

The React Native layer should receive domain events, not raw radio packets.

Illustrative TypeScript surface:

```ts
type MeshStatus = {
  state: 'stopped' | 'starting' | 'active' | 'degraded';
  directPeers: number;
  queuedMessages: number;
};

type DeliveryState =
  | 'queued'
  | 'relaying'
  | 'delivered'
  | 'read'
  | 'expired'
  | 'failed';

interface MeshModule {
  start(): Promise<void>;
  stop(): Promise<void>;
  sendMessage(circleId: string, plaintext: string): Promise<string>;
  getStatus(): Promise<MeshStatus>;
  subscribeToMessages(listener: (event: unknown) => void): () => void;
  subscribeToDelivery(listener: (event: unknown) => void): () => void;
}
```

The final Expo application requires a custom development build. Expo Go cannot host the custom Kotlin and Swift networking modules.

## Persistence ownership

The native engine is authoritative for:

- Cryptographic identity
- Encrypted outbox
- Relay queue
- Deduplication state
- Delivery acknowledgements
- Transport state

The UI may cache presentation data, but it must reconcile with the native engine after launch rather than assuming the JavaScript process remained active.

## Future shared implementation

Android begins in Kotlin and iOS will use Swift. Both implementations must share a written protocol specification and identical test vectors. Moving packet encoding, routing, or cryptography into Rust or C++ may be evaluated only after the protocol stabilizes and cross-platform duplication becomes measurable.
