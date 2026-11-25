package edu.inventory.node.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.inventory.contracts.v1.ReplicationEvent;
import edu.inventory.domain.EventEnvelope;
import edu.inventory.domain.EventType;
import edu.inventory.node.core.EventPayload.EpochStarted;
import edu.inventory.node.core.EventPayload.PolicyChanged;
import edu.inventory.node.core.EventPayload.ProductCreated;
import edu.inventory.node.core.EventPayload.StockIn;
import edu.inventory.node.core.EventPayload.StockOut;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Event, JSON and ReplicationEvent conversions; the T23 groundwork (replica rebuilds request_payload). */
class EventCodecTest {

    private final EventCodec codec = new EventCodec();
    private final ObjectMapper reader = new ObjectMapper();

    static List<EventPayload> payloads() {
        return List.of(
                new ProductCreated("P-01", "Laptop", "pcs"),
                new StockIn("P-01", 10, EventCodec.voucherNo(EventType.STOCK_IN, 2, 7)),
                new StockOut("P-01", 3, EventCodec.voucherNo(EventType.STOCK_OUT, 2, 7)),
                new PolicyChanged(PolicyParameter.BATCH_SIZE, 5, 2),
                new EpochStarted("S2", "S2"));
    }

    @ParameterizedTest @MethodSource("payloads")
    void eventSurvivesTheRoundTripThroughReplicationEvent(EventPayload payload) {
        EventEnvelope event = event(payload);

        ReplicationEvent wire = codec.toReplicationEvent(event);
        EventEnvelope back = codec.fromReplicationEvent(wire);

        assertEquals(event, back);
        assertEquals(payload, codec.payload(back));
        assertEquals(wire, codec.toReplicationEvent(back));
    }

    @ParameterizedTest @MethodSource("payloads")
    void payloadJsonHoldsOnlyTheRecordFields(EventPayload payload) {
        JsonNode json = tree(event(payload).payloadJson());

        assertFalse(json.has("type"), json::toString);
        assertEquals(payload.getClass().getRecordComponents().length, json.size(), json::toString);
    }

    @Test void replicaRebuildsTheRequestPayloadThePrimaryStored() {
        String primaryCreate = codec.commandRequestPayload(EventType.CREATE_PRODUCT, "P-01", "Laptop", "pcs", 0);
        String primaryStockIn = codec.commandRequestPayload(EventType.STOCK_IN, "P-01", "ignored", "ignored", 10);
        String primaryPolicy = codec.policyRequestPayload(PolicyParameter.BATCH_SIZE, 5);

        assertEquals(tree(primaryCreate), tree(codec.requestPayload(replicated(new ProductCreated("P-01", "Laptop", "pcs")))));
        assertEquals(tree(primaryStockIn), tree(codec.requestPayload(replicated(new StockIn("P-01", 10, "IN-2-7")))));
        assertEquals(tree(primaryPolicy), tree(codec.requestPayload(replicated(new PolicyChanged(PolicyParameter.BATCH_SIZE, 5, 2)))));
        assertEquals(tree("{\"operation\":\"STOCK_IN\",\"productCode\":\"P-01\",\"quantity\":10}"), tree(primaryStockIn));
    }

    @Test void responsePayloadCarriesTheCommittedPositionAndVoucher() {
        EventEnvelope stockOut = event(new StockOut("P-01", 3, "OUT-2-7"));
        JsonNode response = tree(codec.responsePayload(stockOut));

        assertEquals(stockOut.eventId().toString(), response.get("eventId").asText());
        assertEquals(2, response.get("epoch").asLong());
        assertEquals(7, response.get("seq").asLong());
        assertEquals("OUT-2-7", response.get("voucherNo").asText());
        assertFalse(tree(codec.responsePayload(event(new ProductCreated("P-01", "Laptop", "pcs")))).has("voucherNo"));
        assertEquals(tree("{\"accepted\":true,\"configurationVersion\":2}"),
                tree(codec.responsePayload(event(new PolicyChanged(PolicyParameter.BATCH_SIZE, 5, 2)))));
    }

    @Test void epochStartHasNoRequest() {
        EventEnvelope epochStart = event(new EpochStarted("S2", "S2"));

        assertNull(epochStart.requestId());
        assertNull(codec.requestPayload(epochStart));
        assertNull(codec.responsePayload(epochStart));
        assertEquals("", codec.toReplicationEvent(epochStart).getRequestId());
    }

    @Test void malformedReplicationEventsAreRejected() {
        ReplicationEvent stockIn = codec.toReplicationEvent(event(new StockIn("P-01", 10, "IN-2-7")));
        ReplicationEvent create = codec.toReplicationEvent(event(new ProductCreated("P-01", "Laptop", "pcs")));
        ReplicationEvent epochStart = codec.toReplicationEvent(event(new EpochStarted("S2", "S2")));
        ReplicationEvent policy = codec.toReplicationEvent(event(new PolicyChanged(PolicyParameter.DELAY_SECONDS, 7, 2)));

        List<ReplicationEvent> malformed = List.of(
                stockIn.toBuilder().setQuantity(0).build(),
                stockIn.toBuilder().setVoucherNo("").build(),
                stockIn.toBuilder().setProductName("Laptop").build(),
                create.toBuilder().setQuantity(5).build(),
                stockIn.toBuilder().setEventId("not-a-uuid").build(),
                stockIn.toBuilder().setRequestId("").build(),
                stockIn.toBuilder().setSchemaVersion(2).build(),
                epochStart.toBuilder().setRequestId(UUID.randomUUID().toString()).build(),
                epochStart.toBuilder().clearEpochStart().build(),
                policy.toBuilder().setPolicyChange(policy.getPolicyChange().toBuilder().setNewValue(3601)).build(),
                policy.toBuilder().setPolicyChange(policy.getPolicyChange().toBuilder().setConfigurationVersion(1)).build(),
                create.toBuilder().setPolicyChange(policy.getPolicyChange()).build());
        for (ReplicationEvent bad : malformed) {
            assertThrows(IllegalArgumentException.class, () -> codec.fromReplicationEvent(bad), bad::toString);
        }
    }

    @Test void voucherNumbersFollowTheDesign() {
        assertEquals("IN-1-12", EventCodec.voucherNo(EventType.STOCK_IN, 1, 12));
        assertEquals("OUT-3-40", EventCodec.voucherNo(EventType.STOCK_OUT, 3, 40));
        assertThrows(IllegalArgumentException.class, () -> EventCodec.voucherNo(EventType.CREATE_PRODUCT, 1, 1));
    }

    private EventEnvelope event(EventPayload payload) {
        UUID requestId = payload.type() == EventType.EPOCH_START ? null : UUID.randomUUID();
        return codec.envelope(UUID.randomUUID(), 2, 7, requestId, payload);
    }

    /** What a replica holds after receiving the primary's event over the wire. */
    private EventEnvelope replicated(EventPayload payload) {
        return codec.fromReplicationEvent(codec.toReplicationEvent(event(payload)));
    }

    private JsonNode tree(String json) {
        try {
            return reader.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
