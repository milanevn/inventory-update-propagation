/**
 * Replication engine: outbox dispatch, ordered idempotent apply on replicas, ACK/retry, duplicate and
 * gap detection, catch-up via FetchEvents, and the gRPC implementation of
 * {@link edu.inventory.domain.ReplicationTransport}.
 *
 * <p>Decides HOW events move; WHEN an event is eligible on a route is decided in
 * {@code edu.inventory.node.policy}.
 */
package edu.inventory.node.replication;
