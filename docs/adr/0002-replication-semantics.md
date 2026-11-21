# ADR-0002: Single writer + local transactional outbox
**Status:** Accepted for kickoff · **Date:** 2026-11-21

**Decision:** One active writer (initially S1), client success after local ACID commit including operation event and outbox, no synchronous replica ACK. Events carry stable UUID and ordered epoch/seq; at-least-once delivery with idempotent apply. S1→S2 immediate; S1→S3 min n-second delay; S2→S4 exact n-count batch, no timer flush. Distinct `delaySeconds` and `batchSize` proposed.

**Consequences:** Replica reads can be stale, S4 may indefinitely hold partial batch, and S1 permanent loss may lose acknowledged writes not copied to any survivor. Do not claim global strong consistency or zero data loss.
