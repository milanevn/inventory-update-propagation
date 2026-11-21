# 02 — Architecture & data model

## Topology: same code, four roles

```text
CLI / optional JavaFX client
           |
    gRPC inventory command
           v
    S1 (PRIMARY) + PostgreSQL S1
        |                   \
        | IMMEDIATE          \ DELAY_SECONDS (n)
        v                    v
    S2 (REPLICA)         S3 (REPLICA)
        |
        | BATCH_COUNT (n newly applied events)
        v
    S4 (REPLICA)
```

S1, S2, S3, and S4 are independent JVM processes with independent PostgreSQL instances and configurable host:port. Do not create four duplicated server codebases. Node identity is not the same as authority: a local `PRIMARY` role flag cannot by itself prevent split brain.

## Layers
- **Contracts:** `.proto` wire types/services; generated stubs; no business logic.
- **Common domain:** transport-neutral immutable events and ports/interfaces; no dependency on grpc.
- **Node server / transport:** accepts/authenticates gRPC calls, maps protobuf ↔ domain, returns gRPC status. No SQL in transport controllers.
- **Node server / application:** validates commands and enforces sole-writer policy, stores idempotent receipts.
- **Node server / persistence:** JDBC transactions and schema migrations; local database owns atomicity.
- **Node server / replication engine:** outbox dispatcher, retry, contiguous progress, duplicate suppression and gaps.
- **Node server / replication policies:** decides when events become eligible for each route.
- **Node server / recovery:** snapshot validation, log replay, quarantine; optional safe operator-controlled promotion.
- **CLI / JavaFX:** uses the same gRPC protocol; no direct database access.

## Transaction: primary write
1. Check request ID; reject writes if node is not authorized primary.
2. Acquire `SELECT ... FROM node_state WHERE singleton=TRUE FOR UPDATE` in one JDBC transaction.
3. Validate business constraints, then apply product/stock change. For stock out use a conditional SQL update ensuring stock >= quantity.
4. Compute next contiguous sequence as `last_issued_seq + 1` under the same row lock (not a nontransactional PostgreSQL sequence).
5. Insert event into `operation_log`, insert outbox records for S2 and S3, insert durable client request result.
6. Update `node_state`, commit. Only now respond success to client. Dispatch work must occur *after* the transaction commits.

S3 outbox events are first recorded with no eligible time. The post-commit scheduler observes each committed event and assigns `eligible_at >= observed_commit_time + n seconds`; recovery after crash must also find unscheduled events. This is intentionally conservative: it may send late, but never earlier than n seconds after commit. Configuration version/policy value should be captured when the event is accepted.

## Transaction: replica apply
1. Authenticate peer; check destination, epoch, event identity, checksum (when implemented), seq and source route.
2. Begin transaction and serialize apply using `node_state ... FOR UPDATE`.
3. If sequence already applied: verify matching history; respond duplicate without changing inventory.
4. If sequence skips expected next: respond gap, do not apply.
5. Apply business effects, append original event to `operation_log` and advance contiguous progress atomically.
6. At S2, insert a forwarding outbox entry for S4 *in the same transaction*. Commit and ACK.
7. Batch receivers should apply each batch atomically, or explicitly report the durable contiguous prefix and make replays safe.

S2 forwards the *original* event ID and `(epoch, seq)`, not newly generated IDs.

## Database schema
Flyway migration: `node-server/src/main/resources/db/migration/V1__initial_schema.sql`.

Business tables: `products`, `stock_in`, `stock_out`, `inventory`. Technical tables: `node_state`, `operation_log`, `outbox`, `replication_progress`, `processed_requests`, `replication_policy`, `snapshot_metadata`.

**Trade-off:** This project's global transactional sequence row lock limits peak write concurrency but makes event ordering and gap detection straightforward for small load. `event_hash` and `snapshot_metadata` alone do not establish correctness; implement validation and trustworthy recovery provenance.

## Local deployment
- JVM nodes S1..S4 on gRPC ports **9101..9104**.
- Independent PostgreSQL Docker containers, localhost ports **5541..5544**.
- Default S1=PRIMARY, S2/S3/S4=REPLICA.
- Four independent containers/volumes are essential for node-specific database-failure tests. Docker is optional; separate manually configured PostgreSQL instances also work.

## Physical deployment
- One node JVM + one independent PostgreSQL per machine; e.g. application port 9100 on each host.
- All peer addresses via config/hostnames, no hardcoded localhost. Keep PostgreSQL private to local node.
- LAN firewall should allow only required gRPC node-to-node connections; enable TLS/mTLS and authentication outside trusted demo network.

## Explicit boundaries
- No PostgreSQL logical/streaming replication; no PG publication/subscription.
- No automatic Raft election; failure detection and authority/fencing are different problems.
- No direct DB access from client or another node.
- The current starter includes migration, gRPC status, proto and CLI smoke test. **Business and replication implementations are intentionally not yet included.**
