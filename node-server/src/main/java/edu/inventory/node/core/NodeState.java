package edu.inventory.node.core;

/** The single {@code node_state} row: durable role, epoch, progress and node status. */
public record NodeState(String role, long epoch, long lastIssuedSeq, long lastAppliedSeq, String status) {
}
