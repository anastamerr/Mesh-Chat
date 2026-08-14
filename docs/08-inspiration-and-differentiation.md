# Inspiration and Differentiation

**Status:** Working product and protocol direction
**Last updated:** 2026-08-13

## Why this document exists

Bitchat demonstrates that a hybrid BLE mesh, store-carry-forward couriers, and decentralized internet relays can be implemented on modern phones. It is valuable prior art and its public-domain implementations are useful research material.

Our goal is not to reproduce Bitchat with a different interface. Every borrowed concept must be evaluated against our reliability, privacy, and private-group goals.

Primary references:

- [Bitchat protocol whitepaper](https://github.com/permissionlesstech/bitchat/blob/main/WHITEPAPER.md)
- [Bitchat iOS/macOS implementation](https://github.com/permissionlesstech/bitchat)
- [Bitchat Android implementation](https://github.com/permissionlesstech/bitchat-android)

## Concepts worth learning from

- A transport-independent message router
- BLE devices operating as both clients and relays
- TTL, deduplication, jitter, and bounded forwarding
- Persistent encrypted sender outboxes
- Store-carry-forward courier envelopes
- Recipient-authenticated delivery and read receipts
- Optional decentralized internet relays
- Protocol compatibility between native Android and iOS implementations
- Explicit queue, payload, retention, and copy limits

These are patterns to study, test, and improve rather than requirements to copy unchanged.

## Deliberate differences

### Private circles first

The primary product model is a trusted circle created through an invitation. Public local channels may be explored later, but they must not define the identity, privacy, or routing architecture.

### Reliability as a product feature

The project will measure delivery probability, delay, duplicate overhead, battery cost, and failure reasons. It will expose truthful user-facing delivery states and develop adaptive multipath routing only when real traces demonstrate an improvement over controlled forwarding.

### Metadata privacy from the start

Permanent identities, nicknames, group identifiers, and neighbor lists should not be broadcast in cleartext in a production protocol. Rotating radio identities and private contact recognition are protocol goals rather than unspecified future cleanup.

### Multiple local transports

BLE is the initial transport and may remain the control and discovery plane. The architecture must permit higher-bandwidth local Wi-Fi transports, community nodes, and internet gateways without changing message semantics.

### Stable decentralized participants

Old phones, laptops, or small community-operated nodes may act as persistent encrypted relays. They improve availability without becoming an authoritative central service.

### Coordination beyond chat

Private check-ins, meeting points, and time-stamped location sharing give groups a reason to adopt the product before a connectivity failure.

## Competitive thesis

> Build the decentralized messenger that users trust for private group coordination because it can explain, measure, and improve how messages survive disconnected networks.

The strongest competitive claim is not absolute range or guaranteed delivery. It is a combination of:

- Infrastructure-independent local operation
- Honest, authenticated delivery semantics
- Bounded redundant routes
- Strong content and metadata privacy
- Useful coordination workflows
- An approachable cross-platform experience

## Rules for using prior art

- Confirm the applicable license in the exact repository and revision before reusing code.
- Credit designs and implementations that materially influence the project.
- Do not inherit cryptographic or privacy decisions without an independent threat-model review.
- Prefer protocol interoperability only when it supports the product goals and does not freeze known weaknesses.
- Maintain independent tests and documented rationale for security-sensitive decisions.
- Treat competitor marketing claims as hypotheses until reproduced on physical devices.
