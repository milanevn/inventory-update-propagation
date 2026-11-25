/**
 * Durable node core shared by the primary write path, replicas and replay: the single
 * {@code node_state} row (lock, sequence numbers, progress, durable role and status) and the
 * {@code operation_log}.
 *
 * <p>Write-path methods join the caller's transaction and never open their own, so the caller commits
 * business rows, {@code operation_log}, {@code processed_requests} and {@code node_state} atomically.
 * Deciding when to write (role checks, deduplication, recovery transitions) belongs to the calling
 * package.
 *
 * <p>Design: docs/thiet-ke.md, sections 3 and 4.
 */
package edu.inventory.node.core;
