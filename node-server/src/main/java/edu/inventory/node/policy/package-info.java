/**
 * Propagation policies: immediate (S2), delay of {@code delaySeconds} (S3) and batches of
 * {@code batchSize} business events (S4); durable scheduling across restarts and policy changes
 * at runtime, replicated as POLICY_CHANGE events. Routes are fixed per epoch at promote time.
 *
 * <p>Design: docs/thiet-ke.md, section 4.
 */
package edu.inventory.node.policy;
