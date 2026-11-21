# ADR-0001: Java 21 + gRPC/Protobuf + PostgreSQL
**Status:** Accepted for kickoff · **Date:** 2026-11-21

**Context:** Five developers, deadline 18 Dec, assignment requires independent updates over four nodes; no web development, no built-in database replication.

**Decision:** Java 21, Spring Boot non-web, gRPC Java and Protobuf for RPC, Spring JDBC/Flyway and independent PostgreSQL on each node; CLI initially, JavaFX optional UI.

**Alternatives:** Java RMI (simpler all-Java, tight serialization coupling), bare TCP (framing, RPC/error handling and security overhead), REST (unnecessary web APIs), Kafka (additional infrastructure).

**Consequences:** Proto defines versionable contract; team must learn generated stubs and RPC errors. gRPC provides transport, **not** persistence or exactly-once application.
