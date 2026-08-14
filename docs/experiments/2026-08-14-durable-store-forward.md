# Durable Store-and-Forward Experiment

**Date:** 2026-08-14
**Result:** Pass

## Objective

Prove that an encrypted packet can enter an offline relay, survive reconstruction from disk, reach its recipient later exactly once, and produce authenticated delivery state at its sender.

## Scenario

```text
1. A <-> B; C is disconnected.
2. A persists and sends ciphertext to B.
3. B persists the decremented canonical packet.
4. B is reconstructed from the same directory.
5. B <-> C becomes available.
6. C persists and decrypts the message.
7. C signs an acknowledgement and sends it C -> B -> A.
8. A verifies and persists the receipt before removing its outbox packet.
```

## Storage properties exercised

- Canonical encoded packets; no second serialization format
- Forced file content followed by an atomic move in the same directory
- Receipt-before-outbox-removal ordering
- Startup cleanup of incomplete temporary writes
- Fail-closed corrupt-state and filename/content validation
- Rejection of symbolic-link packet entries
- Hard global packet-count and byte bounds
- Persistent expiry removal and exactly-once recipient state
- Same logical packet recognition despite mutable hop/copy budgets
- Conflict detection when immutable fields or ciphertext differ under one packet key

## Tests

```shell
./gradlew clean test --offline --no-daemon --rerun-tasks
```

| Suite | Tests | Skipped | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| `PacketCodecV0Test` | 5 | 0 | 0 | 0 |
| `HpkeCandidateTest` | 4 | 0 | 0 | 0 |
| `SenderSignatureCandidateTest` | 4 | 0 | 0 | 0 |
| `DirectoryPacketStoreTest` | 8 | 0 | 0 | 0 |
| `MeshNodeTest` | 3 | 0 | 0 | 0 |
| `ThreeNodeMeshTest` | 5 | 0 | 0 | 0 |
| **Total** | **29** | **0** | **0** | **0** |

The 100-message scenario creates 100 distinct HPKE ciphertexts while C is unavailable, retains all 100 at B, connects B to C, decrypts every recipient delivery, and confirms 100 unique message IDs.

At this checkpoint, the production runtime graphs contained only Kotlin stdlib and project modules and Tink remained test-scoped. The later cryptographic interoperability checkpoint moved Tink into its own production module without adding it to the engine.

## Review findings incorporated

The post-implementation review resulted in these changes before acceptance:

- Replaced boolean deduplication with `ABSENT`, `KNOWN`, and `CONFLICT` classification.
- Defined logical equality to exclude mutable routing budgets while binding ciphertext and immutable metadata.
- Rejected corrupt or non-regular persisted entries instead of silently ignoring them.
- Avoided unnecessary directory synchronization when expiry cleanup makes no change.
- Bound acknowledgement signatures to protocol domain, version, type, message ID, sender routing token, and timestamps in the test construction.
- Required an existing private outbox packet before a verified acknowledgement may create delivery state.

## Limits of the evidence

- The then-open cross-platform cryptographic format is now covered by the [Kotlin–Swift interoperability experiment](2026-08-14-crypto-interoperability.md).
- The directory store assumes one process owner per node directory.
- The test covers ordinary process reconstruction, not sudden power loss or storage hardware failure.
- Relay copies are retained until expiry because relay-side acknowledgement authentication/deletion is not yet defined.
- Operating-system process isolation is covered separately by the [multi-process loopback experiment](2026-08-14-multi-process-loopback.md).
- Packet loss, randomized delay, concurrency, BLE, Android lifecycle, and battery behavior remain untested.
