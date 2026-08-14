# Phase 1 Experiment Plan

**Status:** Proposed
**Last updated:** 2026-08-13

## Purpose

The experiment exists to invalidate incorrect assumptions early. A visually convincing chat demo is not enough; the test must show that a real relay carried an encrypted message while no direct or internet route was available.

## Equipment

- Three physical Android phones with documented models and OS versions
- A development workstation with Android tooling
- A reproducible way to disable internet and cellular data
- A test location or shielding/layout that prevents A from reaching C directly while preserving A-B and B-C links
- Charging equipment for longer battery trials

## Instrumentation

Each device records monotonic timestamps and local structured events:

- Discovery started and stopped
- Peer discovered and lost
- Connection attempt and result
- Packet queued, transmitted, received, forwarded, dropped, or expired
- Deduplication hit
- Fragment creation and reassembly result
- Decryption or authentication result without sensitive data
- Acknowledgement created, forwarded, verified, or rejected
- Queue depth and byte usage
- Battery level snapshots during dedicated trials

Events use local random experiment identifiers. Logs must not include plaintext, private keys, or full encrypted payloads.

## Baseline scenarios

### E1: Direct delivery

```text
A <-> C
```

Validate discovery, encryption, decryption, persistence, and acknowledgement without a relay.

### E2: Required relay

```text
A <-> B <-> C
```

Prevent direct A-C communication. Demonstrate that B forwarded the message and acknowledgement.

### E3: Duplicate paths

Introduce a second valid route or intentionally repeat packets. Confirm that C displays one logical message and that A accepts one delivery state transition.

### E4: Recipient disappears

Disconnect C after A sends. Confirm B retains the sealed message and forwards it when C returns.

### E5: Relay restart

Restart B after it accepts the message but before C is available. Confirm the encrypted relay queue recovers and delivery continues.

### E6: Sender restart

Restart A while its message is queued. Confirm outbox recovery and acknowledgement reconciliation.

### E7: Tampering

Modify authenticated message bytes in a development test harness. Confirm rejection, no display, and no valid acknowledgement.

### E8: Expiration

Use a short experimental retention period. Confirm every device removes or rejects the expired message.

### E9: Load run

Send at least 100 small text messages through B. Record loss, latency, duplicates, retries, and queue growth.

## Metrics

- End-to-end delivery rate
- Median, p95, and maximum delivery latency
- Peer discovery latency
- Connection establishment latency
- Forwarded bytes per delivered payload byte
- Duplicate packets received and suppressed
- Retry count per message
- Queue high-water mark
- Fragment loss and reassembly failures
- Authentication failures
- Battery consumption over a fixed-duration active test
- Failure classifications rather than only a total failure count

## Evidence for a valid relay test

A test run is valid only if:

- Internet and cellular data are disabled on all devices.
- Diagnostic evidence shows no direct A-C session.
- B logs receipt and forwarding of the matching opaque packet ID.
- C verifies and displays the message.
- A verifies C's acknowledgement.

## Exit criteria

Phase 1 may advance when:

- All scenarios E1-E9 have reproducible scripts or written procedures.
- The 100-message relay run meets the functional definition of done.
- Failures are classified and understood.
- The team has measured rather than guessed payload, timing, and queue limits.
- Results are summarized in a dated report under `docs/experiments/`.

No delivery-rate target is declared before the first baseline measurements. After the baseline, Phase 2 will set explicit reliability objectives.
