# 06 — Team handover, module contracts and backlog

**Kickoff target:** 21–23 Nov 2026. **Delivery:** 18 Dec 2026. **Five developers including Tech Lead.** No member owns a server-specific fork. Everyone writes distributed logic or tests, not just UI or documentation.

## Workstream ownership

| Owner | Main responsibility | Required deliverables | Reviewer |
|---|---|---|---|
| TV1 (Lead) | Domain, transactions, sequencing, integration | Primary command service, JDBC repos, idempotent client receipt, CI merge management | TV2 |
| TV2 | gRPC transport, event applicator, ordering | ApplyEvents/FetchEvents, send/ACK/retry, gap/dup/conflict checks, transport adapter | TV4 |
| TV3 | Replication policies & durable dispatch | S1→S2 immediate, S1→S3 time delay, S2→S4 n-batch + config persistence | TV2 |
| TV4 | Recovery & node health | Snapshot, replay, quarantine, consistency validation, safe fail-closed primary crash behavior | TV5 |
| TV5 | CLI/optional JavaFX, observability & fault E2E | Node status/lag, commands client, injection harness + T08–T20 E2E and deployment docs | TV1 |

**Shared rule:** Each TV authors its own unit tests and must review another member's PR. TV5 also implements distributed testing orchestration, not merely UI. Lead owns integration but not all coding.

## Contract ownership
- `.proto`: TV2 drafts, TV1 approves; any breaking change must be agreed with affected owners before merge.
- `EventEnvelope` & `ReplicationTransport`: TV2, reviewed by TV3/TV1.
- `operation_log`, `outbox`, transaction boundary: TV1; TV3 reviews outbox queries/locking.
- `replication_policy` config and versioning: TV3, TV1 approves.
- `snapshot_metadata`, restore procedure: TV4, TV2 reviews.
- `node_state` role/epoch: TV4, TV1 approves; no unilateral authority decisions.
- Test harness & acceptance report: TV5, all module owners sign off.

## PR workflow
1. `main` is demonstrable/release; `develop` is integrated. Feature branches: `feature/tv1-...`, ..., `feature/tv5-...`.
2. No direct push to main or develop. Small PRs preferably <= 2 days of work.
3. PR description must include issue, changed contract/schema, tests, expected failure behavior, and rollback/migration implications.
4. One reviewer minimum; protocol, DB or authority changes require Tech Lead approval too.
5. CI green and reviewer approval before merge. If DB schema changes, use *new* Flyway migration; never edit an already applied production migration.
6. Daily 10-minute sync; integration demo every 2–3 days.

## Initial sprint backlog (clear Definition of Done)

### TV1
- TV1-01: Product, stock-in/out JDBC transactional services + positive-quantity constraints (T01,T11).
- TV1-02: Transactional `last_issued_seq`, append operation log and two outbox routes atomically (T01,T02).
- TV1-03: `processed_requests` stable result on same client request id (T06 variant).

### TV2
- TV2-01: Implement `ReplicationService.ApplyEvents`; peer auth, src/dest, ordered idempotent apply (T06,T07).
- TV2-02: Implement `FetchEvents`, bounded ordered ranges and gap response (T07,T08).
- TV2-03: gRPC transport adapter with deadline + persisted ACK/retry mechanism (T08,T09).

### TV3
- TV3-01: Outbox worker immediate S1→S2 (T02,T03).
- TV3-02: Post-commit n-second due scheduling persisted and restart-safe (T04).
- TV3-03: Deterministic n-event S2→S4 batch builder, in-flight batch membership and policy config version (T05,T10,T19).

### TV4
- TV4-01: `GetStatus` enhancement (progress, error, status) and heartbeat failure indicators.
- TV4-02: Quarantine, snapshot export/import, checkpoint-consistent replay (T12).
- TV4-03: Fail-closed on S1 loss; demonstrate why stale replica cannot be blindly promoted (T13,T14).

### TV5
- TV5-01: CLI execute, list/query inventory, change policy (admin) + minimal JavaFX view if time.
- TV5-02: Local four-node orchestration and 4-physical-host runbook (T16,T17).
- TV5-03: Fault injection harness, automated E2E and observable log evidence (T08–T20).

## Integration order
1. Contracts/generated types + migrations + four-node health smoke.
2. One command committed at S1 (business + log + outbox).
3. TV2 applies one event at S2 safely; TV3 dispatches S1→S2.
4. S1→S3 delay, S2→S4 batch + client view.
5. Retries, restarts, corruption recovery; physical-host rehearsal.
6. Optional advanced promotion only after required tests pass.

## Definition of Done
- Compiles on Java 21 with `mvn verify`.
- No separate hardcoded server code; config-only node selection.
- Test covers success, duplicate, timeout/crash and data invariant for the change.
- No secrets committed, error status actionable, structured logs include seq/event id.
- Public interface/proto and DB migration documented, contract reviewer signed off.
- Demonstrated on local cluster, merged to develop after review.

## Schedule
- 21–23 Nov: contracts/design/skeleton and kickoff.
- 24–30 Nov: vertical slice + all 3 policy paths.
- 01–07 Dec: durability, retry, concurrency and recovery basics.
- 08–13 Dec: physical host, full E2E, recovery demo.
- 14–17 Dec: code freeze, report and defense rehearsal.
- 18 Dec: final delivery.

## What is actually delivered by this starter
- Maven 4-module scaffold; proto interfaces; generated-code test; Spring non-web node and gRPC NodeService.GetStatus; PostgreSQL Flyway schema; Docker Compose for four independent PostgreSQL instances; CLI status; CI workflow; design/test/ownership docs.
- **NOT yet implemented:** InventoryService, ReplicationService, active policies, real recovery/snapshot, authenticated gRPC, physical-host test, JavaFX GUI.
- `node.role` is a local initial label only, not evidence of a safe elected primary.
