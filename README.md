# Inventory Update Propagation — Assignment 8 Starter

**Vietnamese onboarding:** [README_VI.md](README_VI.md).

**Team 5 · Delivery 18 December 2026 · Kickoff baseline 21 November 2026**

This is an **initial executable skeleton**, not a finished inventory replication system. It includes documented module contracts, a working gRPC status service, a Java CLI smoke call, and a schema for four independent PostgreSQL nodes. Business operations, event dispatch/apply, retries, recovery and the JavaFX UI remain assigned implementation tasks. See [hand-over](docs/06-handover.md).

## What the system will become

```text
       Java CLI / optional JavaFX
                   |
              gRPC (RPC)
                   |
               S1 primary
               /        \
       immediate      n-second delay
             /            \
           S2              S3
            |
       every n applied events
            |
           S4
```

Each server is a separate JVM with a separate PostgreSQL instance. The codebase must implement logical replication; **PostgreSQL built-in replication is prohibited** for project flows.

## Pinned stack
- Java 21, Maven 3.9+, Spring Boot (non-web), Spring JDBC/Flyway
- gRPC Java + Protobuf; PostgreSQL 16 local demo
- `contracts` = `.proto` / generated gRPC stubs
- `common-domain` = independent domain models and ports
- `node-server` = single executable for S1..S4
- `cli-client` = sample NodeStatus RPC (JavaFX is future work)

Versions are pinned in [pom.xml](pom.xml). Versions/downloads have **not** been verified from the network by the authoring environment; perform `mvn verify` on developer machine or CI before starting feature work.

## Quick start — local four-node smoke

Prerequisites: **JDK 21**, **Maven 3.9+**, and either Docker Compose or four independent local PostgreSQL instances.

1. Start **four separate PostgreSQL containers** (only data services run in Compose):

   ```bash
   docker compose -f deploy/docker-compose.yml up -d
   ```

2. Build all modules (first time Maven downloads dependencies):

   ```bash
   mvn -B -ntp clean install
   ```

3. Start the four JVMs on Linux, macOS or Git Bash:

   ```bash
   ./scripts/run-local.sh
   ```

   On **Windows PowerShell**:

   ```powershell
   .\scripts\run-local.ps1
   ```

   Or launch a single S1 manually:

   ```powershell
   $env:NODE_ID='S1'
   $env:NODE_ROLE='PRIMARY'
   $env:GRPC_PORT='9101'
   $env:DB_PORT='5541'
   java -jar .\node-server\target\node-server-0.1.0-SNAPSHOT.jar
   ```

4. From another terminal, call the **implemented** gRPC NodeService.GetStatus:

   ```bash
   mvn -pl cli-client exec:java -Dexec.mainClass=edu.inventory.cli.NodeStatusCli -Dexec.args=localhost:9101
   ```

   Expected shape: `node=S1 role=PRIMARY epoch=1 issued=0 applied=0 status=READY`.

   Try 9102..9104 for S2..S4. **Other gRPC methods are contracts only and are not implemented yet**.

5. Stop nodes with `./scripts/stop-local.sh` (Linux/macOS/Git Bash) or close the PowerShell consoles. Stop DB containers with `docker compose -f deploy/docker-compose.yml down` (keep volumes). **Do not use `down -v` unless you deliberately want to delete local databases.**

### No Docker?
Install/configure four **independent PostgreSQL instances** with distinct storage directories and ports 5541..5544, each with user `inventory`, database `inventory` and a local password. Or run on four machines with one PostgreSQL per machine (local DB port normally 5432). Set `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` on each JVM accordingly. Do not fake failure isolation using four databases in a single PostgreSQL process.

## Physical hosts — one JVM + DB per machine

Each server has Java 21 and its own PostgreSQL database. Set host-specific environment variables, e.g. on S2:

```bash
export NODE_ID=S2 NODE_ROLE=REPLICA GRPC_PORT=9100
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=inventory
export DB_USER=inventory DB_PASSWORD='<set-locally>'
java -jar node-server/target/node-server-0.1.0-SNAPSHOT.jar
```

CLI from another LAN host: change `localhost:9101` to `<S2-LAN-IP>:9100`. Allow gRPC traffic on the node's firewall. `NodeService.GetStatus` **is not yet authenticated or TLS-protected**, so restrict the initial smoke demo to an isolated trusted LAN. Implement authenticated secure channels before any wider networking. **Peer routing / replication is not implemented in this starter**; TV2/TV3 will add peer config/transport and physical-host tests.

## Source layout

```text
contracts/                Protobuf wire API, generated stubs
common-domain/            EventEnvelope + ReplicationTransport port
node-server/              Spring non-web node, gRPC status, Flyway DDL
cli-client/               GetStatus client smoke
scripts/                  local multi-JVM launcher + offline structural audit
deploy/                   Docker Compose (PostgreSQL only)
docs/                     requirements, architecture, protocol, recovery,
                          test plan, ownership, ADRs, source provenance
.github/workflows/        Maven verify CI
```

## Scope and limitations

| Starter capability | Status |
|---|---|
| Maven multi-module & protocol contract | Provided, **not yet Maven-verified here** |
| Four independent database containers | Compose configured; **not yet runtime-tested here** |
| Flyway business/replication schema | Authored; needs PostgreSQL execution validation |
| gRPC NodeService.GetStatus | Code authored; needs Maven build/smoke |
| CLI GetStatus | Code authored; needs build/smoke |
| InventoryService.ExecuteCommand/GetInventory | **Not implemented** |
| ReplicationService.ApplyEvents/FetchEvents | **Not implemented** |
| Immediate/delay/batch workers | **Not implemented** |
| Recovery/snapshot/manual promotion | **Not implemented** |
| Authenticated/TLS inter-node RPC | **Not implemented** |
| JavaFX UI and fault-injection E2E | **Not implemented** |

This is *not* automatically failover-safe. Primary acknowledgement after local commit does **not** mean the update has reached a replica. See [recovery limitations](docs/04-recovery.md).

## Work process
1. Read [requirements](docs/01-requirements.md), [architecture](docs/02-architecture.md) and [wire semantics](docs/03-protocol.md).
2. Agree on open assumptions before contracts freeze.
3. Create a GitHub private repository (owner action), push `main` and `develop` and protect both branches.
4. Assign [workstreams](docs/06-handover.md), open small PRs into `develop`.
5. For every PR, run `mvn -B -ntp verify` (CI included). Use separate PostgreSQL/Java processes for integration tests; current starter CI does **not** run database-backed E2E.

## Initial repo after unzipping
The supplied ZIP preserves a local `.git` directory with an initial commit and `main` / `develop` branches. It has **no GitHub remote configured**. The team owner must create their own remote repository and run:

```bash
git remote add origin <YOUR-GITHUB-REPO-URL>
git push -u origin main
git push -u origin develop
```

Do not commit `.env` secrets. Defaults in sample files are **local/demo-only**.
