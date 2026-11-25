package edu.inventory.node.core;

/**
 * A sequence number issued on the primary, with the epoch read in the same statement. Callers stamp
 * the event with this epoch, never one read earlier, so the event always matches the node state under
 * which its {@code seq} was issued.
 */
public record IssuedSeq(long epoch, long seq) {
}
