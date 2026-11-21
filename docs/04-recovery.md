# 04 — Failure, recovery and primary authority design

## Failure taxonomy
- **Transport timeout / packet loss**: remote may have committed even if caller receives error. Retry same event ID; do not alter payload.
- **Replica JVM crash**: restart; query durable progress and resume committed pending events.
- **Replica DB corrupted/missing**: quarantine and restore from validated snapshot, then ordered log replay.
- **Primary JVM crash, DB intact**: restart original primary after checking no competing primary was promoted; durable outbox resume.
- **Primary host/data permanently lost**: acknowledged writes not yet replicated may be unrecoverable. Do not claim RPO=0.
- **Partition/split brain**: loss of heartbeats cannot prove a node is dead; refuse unsafe promotion.
- **Two-node (S1+S2) failure**: delayed S3/S4 may be behind; only read-only service unless a safe recovery source/authority can be established.

## Recovery state machine
`READY -> SUSPECT -> QUARANTINED -> RESTORING -> VERIFYING -> CATCHING_UP -> READY`; any validation failure leads to `QUARANTINED`.

1. Stop writes/apply on affected node.
2. Record observed epoch, last applied position, event log hash chain (future), corruption symptoms.
3. Choose verified source and recoverable point; compare history/position, not just latest timestamp.
4. Restore a **consistent** snapshot containing business tables, relevant technical metadata and snapshot checkpoint `(epoch,seq)`.
5. Replay ordered events after snapshot checkpoint via idempotent applicator.
6. Validate business invariants (inventory>=0, matches committed vouchers) and log/event identity.
7. Resume scheduled/batched forwarding only if outbox state was restored/reconstructed correctly.
8. Re-open queries; warn if behind; do not accept write without authorized primary role.

## Operator-controlled primary promotion (optional and hard-gated)
- Ordinary behavior: S1 alone accepts writes. On its failure, cluster stops writes (may keep available replicas read-only with stale-position labels).
- To promote a replica, operator must first **fence the old writer**: prove it is stopped/unable to receive writes, e.g. physically power off, revoke write access, or validated external authoritative lock. A local `role=PRIMARY` flag and epoch increment are **not** sufficient.
- Determine authoritative acknowledged commit high-water mark from reliable evidence. If cannot prove candidate contains all acknowledged writes, explicitly disclose possible data loss; require admin recovery decision before any new writes.
- Choose candidate with verified contiguous history; advance epoch only with a safe authority transition and ensure all clients/receivers reject old epoch's new writes.
- Quarantine any returning old primary and reconcile diverging history before allowing it to rejoin.
- **MVP does not implement automatic promotion**. Existing `node.role` is an initial static profile only, not a distributed fencing implementation.

## S4 policy vs recovery
Normal path only sends full n-sized batches. Administrative recovery may restore S4 from snapshot/log outside normal policy, but must be explicitly invoked, logged and distinguished from normal propagation demonstration; never silently flush the tail under ordinary timing.

## Snapshot integrity
- Snapshot source must be consistent and trusted; use transactional backup or planned write quiescence during demo.
- Save SHA-256 hash, snapshot position, source node and creation time; protect storage from untrusted modification.
- Validate snapshot and replay result before declaring the node healthy. One snapshot from a corrupted source isn't proof of truth.
- Preserve op-log retention until replicas have caught up or a verified recoverable snapshot supersedes history.

## Minimal scope milestone
1. Crash/restart workers with durable outbox and no duplicate application.
2. Replica divergence/stock corruption detection and verified full rebuild using good source.
3. Ordered log catch-up from retained source.
4. Read-only pause on S1 crash (no unsafe automatic failover).
5. Optional manual fenced promotion **only after** tests for stale S1 return and stale candidate.

## Recovery boundaries / honest RPO-RTO
No measured recovery point/time objective is guaranteed at kickoff. Record empirical durations in tests. Asynchronous primary ACK without replica ACK means there is a real acknowledged-write loss window.
