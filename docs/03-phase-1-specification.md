# Phase 1: Android Three-Phone Proof of Concept

**Status:** Ready for technical investigation
**Last updated:** 2026-08-13

## Objective

Prove that a private text message and its authenticated delivery acknowledgement can cross a real three-phone Android chain without internet access or a central server.

## Required topology

```text
A: sender  <---->  B: relay  <---->  C: recipient

A and C must not have a usable direct connection during the test.
```

All testing uses physical Android devices. BLE radio behavior must not be accepted based only on emulators.

## Functional requirements

### Identity and trust

- Each installation creates a cryptographic device identity.
- A and C exchange the information required for private messaging before the test.
- Phase 1 may use a development-only QR or manual bootstrap screen.
- B is not trusted with the plaintext or recipient private keys.

### Discovery and connection

- Each device discovers compatible nearby devices.
- B maintains usable connections to A and C.
- Connection loss and restoration are visible in diagnostics.
- The initial experiment runs with all apps in the foreground.

### Messaging

- A creates a UTF-8 text message addressed to C.
- The message receives a globally collision-resistant identifier.
- A persists the encrypted outbound representation before reporting it as queued.
- B accepts and forwards the encrypted packet.
- C authenticates, decrypts, persists, and displays the message.
- C displays a message ID at most once regardless of duplicate packet arrivals.

### Acknowledgements

- C creates an acknowledgement bound to the original message ID.
- The acknowledgement is authenticated as originating from C.
- The acknowledgement may return through B.
- A marks the message delivered only after verifying it.
- Relay acceptance must not produce the delivered state.

### Store and forward

- If C is temporarily unavailable, B retains an encrypted copy within a strict quota.
- When C returns, B retries delivery.
- Restarting B must not reveal plaintext or silently discard an eligible queued message.
- Expired messages and acknowledgements are removed.

### Forwarding safety

- Every forwardable packet has an expiration and hop limit.
- Devices reject packets with invalid structure or authentication.
- Recently seen messages are deduplicated.
- Queue size, payload size, and retry count are bounded.

## Non-functional requirements

- No internet permission is required for the Phase 1 test build.
- No backend or push notification service participates.
- Protocol code is separated from the test UI.
- Logs never contain plaintext, private keys, or full sensitive payloads.
- The app provides an explicit way to reset development identities and queues.
- All tunable limits are centralized in a protocol configuration object.

## Initial limitations

- Android only
- Foreground only
- Text only
- One-to-one messages
- One relay hop required by the acceptance topology
- No public channels
- No media or location
- No internet gateway
- No adaptive routing
- No promise of production-grade anonymity

## Definition of done

Phase 1 is complete when all of the following are demonstrated repeatedly:

1. A and C exchange at least 100 messages through B with internet disabled.
2. Every accepted message appears exactly once on C.
3. B cannot decrypt message content using any data available to the relay process.
4. Invalid modifications are rejected by C.
5. Valid delivery acknowledgements return to A.
6. A queued message survives restarting A.
7. A relayed message survives restarting B.
8. A temporary C disconnection results in later delivery after reconnection.
9. Expired messages are not delivered.
10. The experiment report contains measured delivery rate, latency, duplicate traffic, and failure reasons.

The 100-message run is an engineering gate, not a claim of production reliability.

## Pre-implementation gates

Before scaffolding the Kotlin project, confirm:

- Minimum Android API level and target device set
- BLE central/peripheral support on the available phones
- Background and foreground-service requirements for later phases
- Maintained cryptographic library and protocol choice
- Packet size and fragmentation constraints from real devices
- Test method for preventing a direct A-to-C link
