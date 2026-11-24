package edu.inventory.domain;

import java.util.UUID;

/**
 * Transport-neutral committed event; wire DTOs belong to contracts.
 *
 * @param requestId originating client or admin request; {@code null} for {@code EPOCH_START}
 * @param payloadJson type-specific fields, stored as {@code operation_log.payload}
 */
public record EventEnvelope(UUID eventId, long epoch, long seq, EventType type, UUID requestId,
                            int schemaVersion, String payloadJson) {
    public EventEnvelope {
        if (eventId == null || epoch < 1 || seq < 1 || type == null || schemaVersion < 1 || payloadJson == null)
            throw new IllegalArgumentException("Invalid committed event");
    }
}
