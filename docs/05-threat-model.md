# Threat Model

**Status:** Draft for Phase 1
**Last updated:** 2026-08-13

## Assets

- Message plaintext
- Device private keys
- Contact and circle membership
- Sender-recipient relationships
- User identity and nickname
- Location and movement patterns
- Message integrity and ordering
- Delivery and read state
- Device battery, storage, and radio capacity

## Trust boundaries

### Trusted

- Sender endpoint at the time it creates a message
- Intended recipient endpoint at the time it decrypts a message
- Reviewed cryptographic library behavior within its documented assumptions
- Platform-protected key storage within the limits of the operating system

### Untrusted

- Every relay phone
- Every community relay
- Every internet gateway
- Nearby Bluetooth observers
- Network transports
- Packets received from peers
- Claimed nicknames and unauthenticated discovery data

## Adversary capabilities considered

An attacker may:

- Passively observe local radio traffic
- Join the nearby network with many identities
- Replay previously observed packets
- Modify, truncate, reorder, duplicate, or drop packets
- Flood discovery and relay queues
- Claim misleading nicknames
- Collude with other relay devices
- Attempt to infer the social graph from metadata
- Carry messages indefinitely or refuse to forward them
- Submit malformed fragments or oversized fields

## Required Phase 1 protections

- End-to-end confidentiality for private message content
- Sender authentication at the recipient
- Integrity protection for message content
- Recipient-authenticated delivery acknowledgements
- Replay and duplicate suppression
- Message expiration and hop limits
- Hard payload, fragment, queue, and retry bounds
- Safe parsing of untrusted binary input
- Encrypted persistent outbox and relay storage where practical
- No private keys or plaintext in logs

## Availability limitations

The system cannot force an untrusted relay to forward a message. Availability comes from redundant routes, retry, store-carry-forward, and independently operated relays. Delivery is never guaranteed when no permitted route or future encounter exists.

The Phase 1 prototype does not defend fully against:

- Radio jamming
- A global passive adversary
- Operating-system or device compromise
- Extraction from an unlocked endpoint
- Traffic analysis using timing and packet volume
- Large coordinated Sybil attacks
- Denial of service by an attacker with overwhelming nearby devices

These exclusions must not be described publicly as solved.

## Metadata privacy target

Production discovery should not broadcast a permanent identity, nickname, circle identifier, or neighbor list in cleartext. Temporary on-air identifiers should rotate, while trusted contacts retain a private method of recognition.

Phase 1 may temporarily use stable development identifiers for debugging, provided that:

- They are clearly marked non-production.
- They contain no real user information.
- The protocol isolates them behind an identity abstraction.
- Their removal is a gate before public field testing.

## Abuse and resource controls

Future relay policy should include:

- Per-peer and per-trust-tier quotas
- Global byte and item caps
- Rate limits for discovery and packet intake
- Bounded copy budgets
- Expiration and deterministic eviction
- Payload-type restrictions for courier delivery
- User-controlled relay and battery modes
- Blocking without revealing private group membership

## Security review gates

Before public release:

1. Freeze and publish the protocol version.
2. Add cross-platform cryptographic test vectors.
3. Fuzz packet and fragment parsers.
4. Test replay, modification, impersonation, and queue-exhaustion cases.
5. Commission an independent security review.
6. Document known metadata exposure and delivery limitations plainly.
