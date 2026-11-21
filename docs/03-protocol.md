# 03 — gRPC communication & replication protocol v0.1

## Contract location
`contracts/src/main/proto/inventory.proto` is the **single source of truth**. Generated Java sources must never be edited manually. Any field/service changes require an ADR + PR reviewed by Tech Lead and contract owner.

**Services**
- `InventoryService`: ExecuteCommand, GetInventory (planned; deliberately not implemented in skeleton).
- `ReplicationService`: ApplyEvents, FetchEvents (planned).
- `NodeService`: GetStatus (**implemented**), UpdatePolicy (planned).

## Result and error semantics
- gRPC `OK` means RPC delivered and responded to; it does not imply events were applied unless `ApplyReply.result` says `APPLIED` or verified `DUPLICATE`.
- Domain rejection: `INVALID_ARGUMENT` for malformed request/quantity; `FAILED_PRECONDITION` for insufficient stock, untrusted role, stale config; `ALREADY_EXISTS` for conflicting command ID; `PERMISSION_DENIED` for unauthorized operation.
- Transport: `UNAVAILABLE` for unreachable peer; `DEADLINE_EXCEEDED` for timeout; `RESOURCE_EXHAUSTED` for oversized batch.
- For `ApplyEvents`, `GAP_DETECTED`, `STALE_EPOCH`, `CONFLICT` must not be silently ACKed as success. `DUPLICATE` requires exact ID/payload match (divergent same-position event is CONFLICT).

## Event identity and ordering
- `event_id` is a UUID stable across retries and forwarding.
- `seq` monotonically increases for the authoritative branch, assigned transactionally at primary. Within a single epoch, no gaps from rolled-back writes.
- `epoch` identifies primary generation. `epoch+seq` alone does not grant write authority and requires operator-verified history continuity across promotion.
- For the MVP, no promotion means epoch stays 1. If promotion later implemented, preserve a contiguous history and fence the former primary before new writes.
- Sending/processing order is the original primary commit order; do not sort using wall-clock timestamps.

## Client ↔ primary
1. Client sends a stable `request_id` UUID for each business command.
2. Primary performs local ACID commit (business state + log + outbox + request receipt).
3. Success response only after commit. S2 ACK is not a prerequisite.
4. Client retry uses **same request_id**. Server returns stored result without writing again.
5. Replica rejects writes with `FAILED_PRECONDITION` rather than silently redirecting.

## Route S1 → S2: immediate
- Upon S1 local commit, immediate outbox event is eligible for dispatch.
- Worker sends unary `ApplyEvents`, bounded batch, ascending contiguous `seq`.
- On timeout/UNAVAILABLE, do not mark ACKED; retry with backoff/jitter, persist the retry plan.
- On applied/confirmed duplicate, advance per-peer acknowledgment cursor and mark outbox ACKED in one local transaction.
- A delayed ACK means delivery status is unknown, not that the event was definitely rejected.

## Route S1 → S3: n-second delay
- `n=delaySeconds`, positive integer. Configuration is persisted; source event captures the effective policy version/value at acceptance.
- S3 eligible time is assigned only after the original commit is observed, yielding a **not-before** guarantee.
- Worker only emits due contiguous events; `n seconds` is a minimum delay, **not a hard-real-time delivery deadline**.
- Changing n affects only newly accepted events; old pending events retain their originally recorded n/due time (chosen for reproducible behavior).
- Unsheduled events after a crash are discovered on restart and scheduled conservatively.

## Route S2 → S4: n-update batch
- Count only distinct, committed, sequential updates applied by S2 (CREATE_PRODUCT, STOCK_IN, STOCK_OUT each count as one).
- A batch comprises the **next n** contiguous, not-yet-ACKed events. It is eligible only when n events exist. No timer flush in normal mode.
- Retry of a previously eligible batch is allowed even if already sent, but never count retry as a new update.
- If a batch fails, preserve its membership and ordering before moving to the next batch.
- A tail of length `0 < tail < n` stays pending, possibly indefinitely. Admin repair/snapshot mode is a distinct protocol, not silent timeout delivery.
- Policy changes use a serialized admin action; new n applies when selecting a new batch, not to an in-flight batch. Persist version and selected batch boundary (must be added by TV3).

## Apply operation and ACK atomicity
1. Validate node auth, source and route, expected epoch and event range.
2. Acquire node apply lock, compare local contiguous cursor and existing event identities.
3. Apply whole accepted contiguous batch in a local transaction including business rows, operation log and cursor; S2 additionally records the S4 forwarding outbox.
4. ACK reports committed contiguous position. Lost ACK can be retried safely.
5. Gaps trigger `FetchEvents(epoch, after_seq, limit)`, with limit 1..500, from trusted source; fetch must not accidentally bypass the normal S4 n-update batching policy.

## Snapshot / catch-up boundary
- `FetchEvents` returns events strictly after the requested position, ascending and bounded.
- If log range was compacted, response indicates `snapshot_required`.
- Snapshot transfer is a separate future protocol, not contained in current `.proto`. MVP can use an operator-initiated offline snapshot procedure; document hash, position, source, and integrity validation.
- No replica may apply events from an unverified new history branch.

## Timeouts, resource limits and observability
- Starter CLI uses a 3-second deadline. Propose per-RPC timeouts: status 2–3s, ApplyEvents 3–5s, FetchEvents 5s. Tune after integration tests, not promises of SLA.
- Retries: exponential backoff with jitter; cap in-flight count and batch size. Retries should outlive process restart via PostgreSQL metadata.
- Structured log: node_id, source/destination, epoch, seq range, event_id, request_id, retry_count, RPC status, elapsed_ms.
- Enforce admin and node authentication; do not trust client-provided epoch/role without fencing/authority checks.

## What gRPC does NOT provide
Durable queue, exactly-once apply, sequence allocation, corruption recovery, correct election, or fencing. All remain explicit responsibilities of app modules.
