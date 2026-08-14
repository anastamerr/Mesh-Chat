# Decentralized Messaging

An offline-first, decentralized messaging project in which phones deliver private messages using the best available path: direct peer-to-peer links, nearby relays, store-carry-forward couriers, community nodes, or optional internet gateways.

The project is currently in the **design and Android proof-of-concept stage**. We are intentionally proving the networking assumptions before building a polished cross-platform application.

## Product promise

> Messaging that does not stop when the internet does.

The network cannot create connectivity where no path or future encounter exists. Its promise is persistent, opportunistic delivery: messages remain encrypted and queued until a permitted route appears or the message expires.

## Local-first invariant

An already-created private circle must be able to exchange messages among reachable members when all company-operated infrastructure and internet connectivity are unavailable.

Internet services may improve speed and reach, but they must not own the group, its identity, or the only copy of its messaging state.

## Current scope

The first technical milestone is a native Kotlin Android experiment involving three physical phones:

```text
Phone A  ->  Phone B  ->  Phone C
 sender       relay       recipient
```

- Internet and cellular data are disabled.
- A and C cannot communicate directly.
- B forwards an end-to-end encrypted text message.
- B cannot read or silently modify the content.
- C displays the message once and returns an authenticated acknowledgement.
- Queued messages survive an app restart.

## Documentation

- [Product vision](docs/01-product-vision.md)
- [System architecture](docs/02-architecture.md)
- [Phase 1 specification](docs/03-phase-1-specification.md)
- [Protocol v0 design](docs/04-protocol-v0.md)
- [Threat model](docs/05-threat-model.md)
- [Experiment plan](docs/06-experiment-plan.md)
- [Roadmap](docs/07-roadmap.md)
- [Inspiration and differentiation](docs/08-inspiration-and-differentiation.md)
- [Phase 0 findings](docs/09-phase-0-findings.md)
- [Engineering standards](docs/10-engineering-standards.md)
- [Phase 0 JVM experiment report](docs/experiments/2026-08-13-phase-0-jvm.md)
- [Three-node simulator experiment](docs/experiments/2026-08-14-three-node-simulator.md)
- [Durable store-and-forward experiment](docs/experiments/2026-08-14-durable-store-forward.md)
- [Decision record: Android-first native mesh](docs/decisions/0001-android-first-native-mesh.md)
- [Decision record: Android platform baseline](docs/decisions/0002-android-platform-baseline.md)
- [Decision record: asynchronous message cryptography](docs/decisions/0003-asynchronous-message-cryptography.md)

## Planned application architecture

The eventual product will use one shared Expo/React Native interface over two native platform implementations:

```text
              Expo / React Native UI
                       |
               TypeScript Mesh API
                       |
          +------------+------------+
          |                         |
    Android engine              iOS engine
       Kotlin                     Swift
```

React Native will own presentation and user interaction. Discovery, radio connections, routing, cryptography, persistent queues, acknowledgements, and background lifecycle handling will remain native.

## Project principles

1. Reliability is measured, not assumed.
2. Relays are untrusted and must only handle opaque encrypted data.
3. A relay accepting a message is not delivery; only the recipient can acknowledge delivery.
4. Duplicate network packets are acceptable, but duplicate user-visible messages are not.
5. Transports are replaceable. The messaging model must not be coupled to BLE.
6. No custom cryptographic primitives.
7. Foreground behavior is proven before background behavior.
8. Product features are added only after the underlying network claim is demonstrated on real devices.

## Verify the protocol core

With JDK 17 available:

```shell
./gradlew clean test --offline --no-daemon --rerun-tasks
```

Omit `--offline` only when resolving pinned dependencies for the first time.

## Status

Phase 0 is active. `mesh-protocol` owns canonical packet encoding, `mesh-engine` owns bounded durable node state and store-and-forward decisions, and `mesh-simulator` supplies deterministic byte-level links. The JVM proof now covers an offline relay, relay and recipient reconstruction, authenticated delivery state, and 100 encrypted messages. A clean offline build passes 29 tests with strict explicit-API and warnings-as-errors checks. Android radio work remains gated on the Android SDK and a physical-device capability matrix.
