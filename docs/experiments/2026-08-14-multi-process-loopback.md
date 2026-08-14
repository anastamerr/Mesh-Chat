# Multi-Process Loopback Experiment

**Date:** 2026-08-14
**Result:** Pass

## Objective

Move the existing durable A–B–C proof across operating-system process and socket boundaries, then verify that a relay forcibly terminated during a partition resumes delivery from disk.

## Scenario

```text
1. Start A and B as separate JVM processes with separate stores.
2. A persists an HPKE ciphertext and sends its canonical packet bytes to B over loopback TCP.
3. C is absent; B's attempted connection fails and its queued packet remains durable.
4. Force-terminate B and restart it with the same store.
5. Start C as a third JVM process and let recovered B deliver the packet.
6. Decrypt the bytes returned from C's delivery store and compare the original plaintext.
7. C constructs and signs the delivery acknowledgement; B relays it to A.
8. Force-terminate and restart C, retry B's retained packets, and verify one user-visible delivery.
```

Each node binds an ephemeral server socket to the loopback interface. Node processes exchange length-bounded canonical packet frames; they do not share `MeshNode`, `PacketStore`, or packet objects. The recipient's acknowledgement signing operation occurs inside C, while signature verification occurs inside A. Test keys are supplied once through process stdin and are not placed in command-line arguments.

## Result

`MultiProcessMeshTest` passes with no skips, failures, or errors. It demonstrates:

- Real JVM process isolation for A, B, and C
- A genuine connection failure while C is unavailable
- Forced relay termination and durable queue recovery
- Canonical byte transfer over loopback TCP
- Recipient-side Ed25519 acknowledgement signing and sender-side verification
- Sender outbox confirmation only after the authenticated receipt returns
- Recipient restart with exactly-once visible delivery under retries

The current clean offline JVM suite contains 38 passing tests.

## Scope discipline

The process launcher, socket command protocol, and acknowledgement construction stay entirely in `mesh-simulator` test sources. They add no production runtime dependency or abstraction and are absent from the simulator JAR. The experiment reuses the production `MeshNode`, `DirectoryPacketStore`, and packet codec directly.

## Limits of the evidence

- Loopback TCP proves process, socket, and serialization boundaries; it does not model BLE discovery, GATT framing, radio range, interference, or throughput.
- The production `MessageCrypto` boundary executes in the parent harness; C's child process stores and returns only the ciphertext packet. A separate API 36 instrumentation suite now covers the Android runtime boundary, but this loopback harness itself remains a JVM process-isolation test.
- The forced termination occurs after B has durably accepted the packet. Interruption during a filesystem write and physical power loss are not claimed.
- Packet loss, randomized delay, concurrent peers, Android lifecycle behavior, and battery cost remain untested.
- The localhost control protocol is test infrastructure, not an authenticated production network protocol.
