# 07 — Kickoff meeting & handover checklist (60–90 minutes)

## Before starting
- Every member: Java 21, Maven 3.9+, Git; either Docker Compose or four separate PostgreSQL instances available to one designated integration laptop.
- Lead shares repository read/write access and branch/PR policies; no production credentials.
- Read docs 01..06 and ADR 0001..0004. Flag any disagreement before implementation.

## Agenda
1. **0–10 min — Problem statement.** Client ↔ S1; S1→S2 immediate; S1→S3 after n secs; S2→S4 after n updates; data damage recovery.
2. **10–25 min — Semantics.** Define S1 commit vs replica ACK, independent n settings, ordering/duplicates, S4 withheld tail.
3. **25–40 min — Architecture.** One JVM codebase, four independent databases, gRPC contracts, domain/transport boundary.
4. **40–50 min — Failover limits.** No automatic safe promotion, show lost-ACK and lost-replica corner cases; recovery scope.
5. **50–65 min — Ownership.** Confirm TV1..TV5 roles/PR reviewers/backlog and deliverable dates.
6. **65–90 min — Live preflight.** Build Maven, start four DBs and nodes, call CLI GetStatus against each port, create feature branches and first PRs.

## Exit criteria
- [ ] All five members can read the repo and know own module/test acceptance criteria.
- [ ] At least the integration machine passes Maven verify + four-node status smoke.
- [ ] Physical-host topology and LAN access to be rehearsed by 13 Dec.
- [ ] `.proto` owner/reviewer explicitly agreed; no unsigned breaking changes.
- [ ] Course-specific open assumptions are documented; lecturer clarification assigned an owner.
- [ ] At least one small PR per member planned for the first 2 days.

## Initial demo expectations
The starter demonstrates **separate JVM + database instances and working status RPC only**. The first integration milestone is a real committed stock operation at S1 flowing to S2, followed by delay and count-based routes. Do not show skeleton health as evidence that replication is implemented.
