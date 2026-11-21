package edu.inventory.domain;

import java.util.UUID;

/** Transport-neutral representation; wire DTOs belong to contracts. */
public record EventEnvelope(UUID eventId, long epoch, long seq, String type, String payloadJson) {
    public EventEnvelope {
        if (eventId == null || epoch < 1 || seq < 1 || type == null || payloadJson == null)
            throw new IllegalArgumentException("Invalid committed event");
    }
}
