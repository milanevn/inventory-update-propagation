package edu.inventory.domain;

import java.util.List;

/** Implement with gRPC; the replication engine should not depend on generated stubs. */
public interface ReplicationTransport {
    DeliveryReceipt applyEvents(String destinationNode, List<EventEnvelope> contiguousEvents);

    record DeliveryReceipt(boolean accepted, long lastAppliedSeq, String message) { }
}
