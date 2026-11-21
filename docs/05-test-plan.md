# 05 — Acceptance & fault-injection test plan

**Ownership:** TV5 leads end-to-end verification; every module owner contributes unit+integration tests. Test plan exists *before* implementation. A test is complete only if it can be run repeatedly with deterministic assertions.

| ID | Type | Scenario | Expected behavior / evidence |
|---|---|---|---|
| T01 | Unit+IT | Create product, stock in, stock out on S1 | Business tables+log+outbox+receipt commit atomically; invalid command rolls back all. |
| T02 | IT | S2 down while S1 commits | Client success after S1 commit; pending outbox durable; no waiting for S2 ACK. |
| T03 | E2E | S1→S2 normal | No intentional delay; S2 applied position catches S1; duplicate not double-applied. |
| T04 | E2E | `delaySeconds=5`, S1→S3 | Before due time no send; after due time sends when available; no hard-real-time assertion. |
| T05 | E2E | `batchSize=3`, S2→S4 | After 1/2 events S4 unchanged; after 3, full batch applied; tail left pending. |
| T06 | IT | Duplicate event ID / same seq | Same event id+content is no-op, different event at same position is conflict. |
| T07 | IT | Receive seq=5 when local is 3 | GAP; no state change; fetch sequence 4 first. |
| T08 | E2E | Network S1↔S2 partition | S1 keeps committed writes; S2 catches up after reconnect from retained log. |
| T09 | E2E | JVM crash after S2 DB commit, before ACK | S1 retry does not duplicate applied inventory; ACK advances safely. |
| T10 | E2E | Restart S2 with pending S4 batch | No lost pending events and no duplicate counts; batch membership stable. |
| T11 | IT | Concurrent stock-out > available quantity | At most valid quantity is sold; inventory nonnegative. |
| T12 | Recovery | Corrupt/erase S3 DB in controlled test | S3 quarantined, restored from verified source, replayed, invariants pass. |
| T13 | Recovery | Stop S1 with newest event not on any replica | Writes stop; stale replica not auto-promoted; acknowledged loss window documented. |
| T14 | Recovery | S1 + S2 down; S3/S4 lagging | Read-only/blocked writes, no unsafe promotion or silent RPO=0 claim. |
| T15 | Recovery | Old S1 reappears after authorized promotion (optional) | Old S1 prevented from receiving writes; reconciliation required before joining. |
| T16 | Deployment | Four JVMs/DBs on one host | Each can be stopped independently; distinct DB instances/ports. |
| T17 | Deployment | Four physical machines, LAN | Peer discovery, auth/network, gRPC, independent Postgres; no localhost dependency. |
| T18 | Contract | Protobuf schema generated | Tests compile and RPC method names/service descriptors match spec. |
| T19 | Config | Change `delaySeconds` or `batchSize` | New settings applied to newly eligible work; in-flight batch stable. |
| T20 | Security | Unauthorized node/admin RPC | Denied; no data changes; error recorded. |

## Test fixture / mechanics
- Deterministic simulated clock for delayed policy unit tests; real clock for a small E2E smoke with tolerant upper bound.
- Test DB per node, independent schema migrations and explicit test data cleanup.
- Fault injection: kill JVM, stop a database container, block selected LAN traffic, deliberately drop a response in a test transport adapter.
- Snapshot and log test includes checksum mismatch and invalid snapshot checkpoint.
- Do not simulate true split-brain safety by merely toggling an in-memory role flag.
- Build passing and demo passing are **separate** gates.

## Required evidence for report
For each distributed test capture node IDs, source/destination, `(epoch,seq)` high watermarks, RPC outcomes, and inventory at S1..S4. Logs must demonstrate before/after, not just success messages. Provide input commands and expected/actual result in report.

## Delivery gates
- **30 Nov:** T01–T05 functional end-to-end.
- **07 Dec:** T06–T11 and T19, durable restart/partition handling.
- **13 Dec:** T12–T18 and T20 on local; four physical LAN host rehearsal.
- **17 Dec:** Freeze; only critical fixes and final presentation rehearsal.

**Current skeleton tests:** protobuf service descriptor + node config validation. All other cases are **planned**, not yet implemented or passed.
