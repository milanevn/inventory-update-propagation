/**
 * Inventory business logic on the PRIMARY: products, stock-in, stock-out, inventory queries.
 *
 * <p>Owns the write transaction (business rows + operation_log + processed_requests + node_state
 * sequence, committed together) and the JDBC repositories for the business tables. There is no
 * outbox: operation_log is the send queue (design decision 17).
 * Exposed to clients through a gRPC adapter in {@code edu.inventory.node.grpc}.
 */
package edu.inventory.node.inventory;
