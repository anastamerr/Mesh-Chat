# Three-Node Mesh Simulator Experiment

**Date:** 2026-08-14
**Result:** Pass

This report records the initial in-memory proof. It is superseded by the [durable store-and-forward experiment](2026-08-14-durable-store-forward.md), which reconstructs nodes from disk and expands the same suite.

## Objective

Prove the first required A–B–C relay flow without claiming that a software simulation validates mobile Bluetooth behavior.

## Topology

```text
A: sender  <---->  B: relay  <---->  C: recipient

A and C have no direct link.
```

Each node owns separate delivery and deduplication state. The deterministic network transfers canonical encoded bytes between nodes in insertion order.

## Command

```shell
./gradlew :mesh-simulator:test --offline --no-daemon --rerun-tasks
```

## Result

| Suite | Tests | Skipped | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| `ThreeNodeMeshTest` | 3 | 0 | 0 | 0 |

Verified behavior:

- A sealed private-message packet follows A → B → C.
- C decrypts the original plaintext with X25519/HKDF-SHA256/AES-256-GCM HPKE.
- B's unrelated recipient key cannot decrypt the ciphertext.
- B has no delivered application message.
- C signs the acknowledgement with Ed25519 and it follows C → B → A.
- A verifies C's acknowledgement; a relay-produced signature fails verification.
- Retrying the same packet does not create a second recipient delivery.
- A packet with no relay hops is stopped at B.
- A later viable copy of that message can still cross B, preventing exhausted-path deduplication poisoning.
- Packet type and message ID jointly identify a network packet, allowing an acknowledgement to bind to the original message ID.

The full repository test run contains 16 passing tests: five codec, four HPKE candidate, four Ed25519 candidate, and three simulator tests.

The simulator production runtime contains only Kotlin stdlib and `mesh-protocol`. Tink is present exclusively in the test configuration.

## What this does not prove

- BLE scanning, advertising, GATT transfer, range, MTU, or fragmentation
- Android permissions, lifecycle, background behavior, or battery cost
- Persistent recovery after process termination
- Packet loss, delay, partitions, or store-carry-forward encounters
- Multipath selection or adaptive routing
- Final cross-platform cryptographic serialization

These remain separate experiments. They are not simulated conclusions.
