/**
 * Recovery and node health: invariant checks (stock = stock-in - stock-out, never negative),
 * quarantine and rebuild of a damaged replica by replaying the operation log (no snapshots),
 * node status beyond READY, manual promote with epoch fencing, and the old primary stepping down
 * to replica when it sees a higher epoch.
 *
 * <p>Design: docs/thiet-ke.md, section 6.
 */
package edu.inventory.node.recovery;
