# ADR-0003: Fail closed rather than invent automatic failover
**Status:** Accepted for MVP · **Date:** 2026-11-21

**Decision:** Restart and data repair are required. Automatic leader election is **not** part of MVP. On S1 loss, stop writes; replicas may serve explicitly stale read-only responses. Any optional manual promotion needs verified freshest history, external fencing of old primary, and documented RPO impact.

**Consequences:** Lower availability under primary failure, but avoids unsafe split-brain claims. Epoch is metadata, not authority consensus.
