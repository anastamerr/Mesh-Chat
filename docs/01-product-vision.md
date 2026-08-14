# Product Vision

**Status:** Accepted for prototype planning
**Last updated:** 2026-08-13

## Problem

Conventional messengers treat an internet connection and centrally reachable service as prerequisites. In crowded venues, remote areas, local outages, and partially connected environments, the phones are still physically present and capable of communicating, but ordinary messaging stops.

The project treats connectivity as a resource rather than a requirement. A user sends a message once; the system decides how to move it.

## Product definition

> A privacy-first group messenger that carries messages, check-ins, and meeting locations across nearby phones and community relays, while using the internet only as an optional acceleration path.

The long-term experience should resemble a familiar private messenger. Users should not have to choose Bluetooth, Wi-Fi, mesh, courier, gateway, or internet delivery.

## Initial adoption wedge

The initial user-facing product is aimed at trusted groups in crowded events and temporary communities:

- Friends at festivals, concerts, stadiums, and conferences
- University groups and controlled campus trials
- Hiking and outdoor groups
- Event staff and field teams
- Families and neighborhood circles during local outages

These groups can onboard in advance, remain within a bounded area, and have an immediate reason to communicate when conventional service becomes unreliable.

Emergency communication may become an important application, but the first releases must not claim to replace emergency services or safety-certified equipment.

## Core user story

1. A user creates a private circle.
2. Other members join through a QR invitation and establish trusted cryptographic identities.
3. A member sends a text, check-in, or location.
4. The system attempts direct local delivery, multi-hop relay, store-carry-forward, or an optional internet route.
5. The sender sees an honest status until the recipient acknowledges the message.

## Delivery semantics

The product distinguishes the following states:

- **Queued:** safely stored on the sender; no route is currently known.
- **Relaying:** at least one permitted relay holds an encrypted copy.
- **Delivered:** the intended recipient produced a valid acknowledgement.
- **Read:** the recipient application produced a valid read receipt, if enabled.
- **Expired:** no acknowledgement arrived before the retention deadline.
- **Failed:** a local permanent error prevents further attempts.

“Sent” must never be presented as “delivered” merely because an intermediate relay accepted the message.

## Differentiation

The project does not compete solely on “Bluetooth messaging.” Its intended advantages are:

- Private trusted circles rather than primarily anonymous public rooms
- Reliable multipath and delay-tolerant delivery
- Transport-independent routing
- Clear, authenticated delivery states
- Strong metadata privacy, including rotating radio identifiers
- Coordination features such as check-ins and meeting locations
- Optional independently operated community relays
- A modern, approachable messenger interface
- A reusable decentralized messaging engine and documented protocol

## Non-goals for the first product

- Replacing WhatsApp or Signal at global scale
- Guaranteed delivery without a physical or internet path
- Real-time voice or video over a BLE mesh
- Unlimited file propagation
- Anonymous public broadcasting
- Safety-critical emergency guarantees
- Cryptocurrency, tokens, or relay payments

## Product success

Technical novelty is insufficient. The product succeeds when a real group installs it for a concrete activity, actively uses its coordination features, and continues communicating through a network disruption without understanding the underlying transports.
