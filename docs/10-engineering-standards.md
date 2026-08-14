# Engineering Standards

**Status:** Active
**Last updated:** 2026-08-13

These rules turn “clean, minimal, modular, efficient” into reviewable engineering constraints.

## Design rules

1. A module has one clear responsibility and exposes the smallest useful API.
2. Protocol, routing, transport, storage, cryptography, and UI remain separate. A convenience import is not a reason to couple them.
3. Add an abstraction only when it protects a real boundary or supports a known second implementation.
4. Prefer immutable values. Copy mutable byte arrays at trust boundaries.
5. Every untrusted or network-controlled size, lifetime, retry count, and queue has a hard bound.
6. Correctness and observability come before clever optimization; optimization follows measurement.
7. No custom cryptographic primitives or accidental dependence on library-specific wire formats.
8. Logs never contain plaintext messages, private keys, full ciphertexts, or stable identifiers that are unnecessary for diagnosis.

## Dependency rules

- The Kotlin standard library and platform APIs are the default.
- A new dependency needs a concrete capability, maintenance and security review, and a reason a small local implementation would be worse.
- Dependencies stay in the narrowest module and configuration that needs them.
- Experimental libraries remain test-scoped until their design decision is accepted.
- Versions and downloaded build distributions are pinned and verified where the tooling supports it.

## Kotlin rules

- Explicit API mode is enabled for library modules.
- Compiler warnings fail the build.
- Public declarations have deliberate visibility and types.
- Domain validation occurs at construction or decoding boundaries.
- Malformed remote input produces typed failures, not partial objects or process crashes.
- Comments explain invariants and non-obvious decisions, not syntax.

## Test rules

- Canonical wire formats require frozen golden vectors.
- Every parser gets valid, boundary, malformed, truncated, oversized, and mutation tests.
- Cryptographic adapters get wrong-key, tamper, context-binding, serialization, and cross-platform vector tests.
- Radio claims require physical-device evidence; mocks cannot prove BLE behavior.
- A test is not complete because it ran once. Its environment, inputs, result, and limitations are recorded.

## Change discipline

A change is complete only when:

- The smallest coherent implementation is present.
- Relevant automated tests pass from a clean build.
- Failure paths and resource bounds are covered.
- The documentation or ADR reflects any changed contract.
- No generated files, speculative utilities, or unrelated refactors were added.

Short code is not automatically simple code. We minimize concepts, dependencies, state, and coupling—not validation, security, or evidence.
