# 01 — Requirements specification (baseline v0.2)

**Scope:** University distributed-systems assignment 8. **Team:** 5. **Deadline:** 18 December 2026.

## Assignment-derived requirements (verbatim in substance)
1. Client interacts with server 1 to manage products, stock-in, stock-out, and inventory.
2. Server 1 immediately propagates updates to server 2.
3. Server 1 propagates updates to server 3 after `n` seconds.
4. Server 2 propagates to server 4 after server 2 has updated `n` times; `n` is configurable.
5. Recover the system when data is damaged.

**Lecture-grounded concepts:** primary-based replication, remote communication, concurrency/transactions, operation-based and checkpoint recovery. Their precise combination below is a project design decision, **not** a quotation from the lecturer.

## Scope acceptance rules
- `FR-01`: Create product (SKU/unique code), stock in, stock out (positive quantity), read inventory.
- `FR-02`: All successful writes go through one active primary (initially S1) and atomically persist inventory change, operation log, outbox, and client idempotency receipt.
- `FR-03`: S1→S2 sends without intentional delay. Client ACK is not blocked by S2 ACK (instructor clarification from project discussion).
- `FR-04`: S1→S3 sends no earlier than n seconds after the commit is observed. Scheduler/transport delay may make it later.
- `FR-05`: S2→S4 sends only when a contiguous group of `batchSize=n` newly applied source updates is available. Retries do not count as new updates. Tail shorter than n remains pending indefinitely in normal mode.
- `FR-06`: Configurable `delaySeconds` and `batchSize` are **separate** admin settings; proposed interpretation pending instructor confirmation if one shared n was intended.
- `FR-07`: S2, S3, S4 can be queried separately; responses report node ID and applied position; replicas may return stale data.
- `FR-08`: Delivery retries, duplicate suppression, gap detection, catch-up, snapshot-based recovery when replay is insufficient.
- `FR-09`: Primary failure must not lead to automatic blind promotion or silent loss of acknowledged-but-not-replicated writes.
- `FR-10`: Same Java source runs as four separate JVMs/DBs on one machine or four physical hosts.

## Deliberate exclusions
Full Raft/Paxos, multi-writer, database-native replication, sharding, Kafka, enterprise UI, and Internet-facing deployment.

## Safety/consistency guarantees
- At primary: local PostgreSQL ACID transaction; command ordering serialized by a transactional row-lock counter.
- Between nodes: at-least-once **attempted delivery** while transport is available and retained log exists, with idempotent apply; no exactly-once network guarantee.
- Eventual catch-up depends on delivery resuming and the policy allowing release. S4's incomplete tail **does not have bounded eventual convergence** without further events or explicit admin recovery.
- A successful S1 write does not imply durability on S2/S3/S4. Permanent loss of S1 before replication can cause acknowledged data loss.
- `epoch` + `seq` are logical ordering metadata, not a consensus/fencing service.

## Key open decisions to confirm
1. Are `delaySeconds` and `batchSize` independent values, despite both being called `n` in the question? **Default: independent.**
2. Does the course expect automatic failover, or only damage recovery? **MVP: data recovery; manual controlled promotion is optional.**
3. Should creating a product count as an update for S4? **Default: yes, one committed event = one update.**
4. Are reads allowed from any node? **Default: yes, with visible replica staleness.**
5. Must a minimum recovery point objective be guaranteed? **None promised beyond locally durable data without additional replication ACK semantics.**

## Definition of acceptable demonstration
1. The three propagation routes behave as specified.
2. Four distinct Postgres instances, not schemas in one shared instance, can be stopped individually.
3. Retry and restart do not double-apply stock changes.
4. A failed replica can catch up from retained events or a verified snapshot.
5. No unsafely promoted stale replica silently accepts writes.
6. Tests and demo work in local mode and four-physical-host mode.
