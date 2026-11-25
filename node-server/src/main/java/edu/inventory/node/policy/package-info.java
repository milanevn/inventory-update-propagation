/**
 * Propagation policies: immediate (S2), delay of {@code delaySeconds} (S3) and batches of
 * {@code batchSize} business events (S4), evaluated from operation_log after every restart (no stored
 * schedule: committed_at for the delay, the S4 cursor for batch boundaries); policy changes at runtime,
 * replicated as POLICY_CHANGE events. Routes are fixed per epoch at promote time.
 *
 * <p>Design: docs/thiet-ke.md, section 4.
 */
package edu.inventory.node.policy;
