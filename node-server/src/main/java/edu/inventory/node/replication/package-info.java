/**
 * Replication engine: per-destination cursor over operation_log (replication_progress.last_acked_seq),
 * ordered idempotent apply on replicas, ACK/retry, duplicate and
 * gap detection, sender-driven catch-up by moving the cursor back (decision 14; FetchEvents only
 * serves replay during recovery), and the gRPC implementation of
 * {@link edu.inventory.domain.ReplicationTransport}.
 *
 * <p>Decides HOW events move; WHEN an event is eligible for a destination is decided in
 * {@code edu.inventory.node.policy}. Design: docs/thiet-ke.md, sections 4 and 5.
 */
package edu.inventory.node.replication;
