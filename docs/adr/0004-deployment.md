# ADR-0004: One codebase, two deployment environments
**Status:** Accepted for kickoff · **Date:** 2026-11-21

**Decision:** All four nodes use the same server code. Local dev: four Java JVMs + four independent PostgreSQL instances/containers; physical deployment: one JVM + one PostgreSQL per physical host. Peer addresses are config, not hardcoded.

**Consequences:** Docker Compose is optional local orchestration, not a runtime dependency. Physical-host test must exercise IP binding, firewall and endpoint config.
