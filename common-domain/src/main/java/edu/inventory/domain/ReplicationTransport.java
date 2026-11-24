package edu.inventory.domain;

import java.util.List;

/** Implement with gRPC; the replication engine should not depend on generated stubs. */
public interface ReplicationTransport {

    /**
     * Sends contiguous events in ascending seq: 1..100 events on the S2/S3 routes, exactly one
     * batch on the S4 route (may exceed 100 when system events fall inside it).
     *
     * @param senderEpoch the sender's current epoch; the receiver checks it, not each event's epoch
     */
    DeliveryReceipt applyEvents(String destinationNode, long senderEpoch, List<EventEnvelope> contiguousEvents);

    /** Receiver's answer, mirrors {@code ApplyReply}. */
    enum ApplyCode {
        APPLIED, DUPLICATE, GAP_DETECTED, STALE_EPOCH, CONFLICT;

        /** Only APPLIED and DUPLICATE acknowledge events; a successful RPC alone does not. */
        public boolean isAck() {
            return this == APPLIED || this == DUPLICATE;
        }
    }

    /**
     * @param receiverEpoch receiver's current epoch
     * @param lastAppliedSeq cumulative ACK: every event with seq up to this value is applied
     */
    record DeliveryReceipt(ApplyCode code, long receiverEpoch, long lastAppliedSeq, String message) {
        public DeliveryReceipt {
            if (code == null || receiverEpoch < 1 || lastAppliedSeq < 0)
                throw new IllegalArgumentException("Invalid delivery receipt");
        }
    }
}
