package edu.inventory.node.core;

import edu.inventory.domain.EventEnvelope;
import edu.inventory.domain.EventType;
import edu.inventory.node.core.EventPayload.EpochStarted;
import edu.inventory.node.core.EventPayload.PolicyChanged;
import edu.inventory.node.core.EventPayload.ProductCreated;
import edu.inventory.node.core.EventPayload.StockIn;
import edu.inventory.node.core.EventPayload.StockOut;
import edu.inventory.node.support.AbstractNodeIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventApplier on the primary write path and on a replica, against PostgreSQL. Groundwork for T01
 * (atomic write, failed command leaves nothing), T03 (replica applies the same rows) and T23
 * (replica stores the same request result).
 */
class EventApplierIT extends AbstractNodeIT {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired NodeStateRepository nodeState;
    @Autowired EventApplier applier;
    @Autowired EventCodec codec;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach @AfterEach void resetNode() {
        tx = new TransactionTemplate(transactionManager);
        for (String table : List.of("processed_requests", "operation_log", "stock_in", "stock_out", "inventory",
                "products", "epoch_history")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE replication_policy SET delay_seconds = 5, batch_size = 3, config_version = 1");
        jdbc.update("UPDATE node_state SET role = 'PRIMARY', epoch = 1, last_issued_seq = 0, last_applied_seq = 0, "
                + "status = 'READY' WHERE singleton = TRUE");
    }

    @Test void primaryAppliesBusinessEventsWithLogAndRequestResults() {
        EventEnvelope create = writeOnPrimary((epoch, seq) -> new ProductCreated("P-01", "Laptop", "pcs"));
        writeOnPrimary((epoch, seq) -> new StockIn("P-01", 10, EventCodec.voucherNo(EventType.STOCK_IN, epoch, seq)));
        EventEnvelope out = writeOnPrimary((epoch, seq) ->
                new StockOut("P-01", 4, EventCodec.voucherNo(EventType.STOCK_OUT, epoch, seq)));

        assertEquals(6L, stock("P-01"));
        assertEquals("IN-1-2", jdbc.queryForObject("SELECT voucher_no FROM stock_in", String.class));
        assertEquals("OUT-1-3", jdbc.queryForObject("SELECT voucher_no FROM stock_out", String.class));
        assertEquals(List.of(1L, 2L, 3L), jdbc.queryForList("SELECT seq FROM operation_log ORDER BY seq", Long.class));
        assertEquals(NOW, jdbc.queryForObject("SELECT committed_at FROM operation_log WHERE seq = 3",
                OffsetDateTime.class).toInstant());
        assertStoredRequest(create, codec.commandRequestPayload(EventType.CREATE_PRODUCT, "P-01", "Laptop", "pcs", 0));
        assertStoredRequest(out, codec.commandRequestPayload(EventType.STOCK_OUT, "P-01", null, null, 4));
        Map<String, Object> response = jdbc.queryForMap("""
                SELECT response_payload ->> 'voucherNo' AS voucher, (response_payload ->> 'seq')::bigint AS seq
                  FROM processed_requests WHERE request_id = ?""", out.requestId());
        assertEquals("OUT-1-3", response.get("voucher"));
        assertEquals(3L, response.get("seq"));
    }

    @Test void replicaAppliesReceivedEventsAndStoresTheSameRequestResults() {
        List<EventEnvelope> primaryEvents = List.of(
                codec.envelope(UUID.randomUUID(), 1, 1, UUID.randomUUID(), new ProductCreated("P-01", "Laptop", "pcs")),
                codec.envelope(UUID.randomUUID(), 1, 2, UUID.randomUUID(), new StockIn("P-01", 10, "IN-1-2")),
                codec.envelope(UUID.randomUUID(), 1, 3, UUID.randomUUID(), new StockOut("P-01", 4, "OUT-1-3")));
        jdbc.update("UPDATE node_state SET role = 'REPLICA' WHERE singleton = TRUE");

        for (EventEnvelope sent : primaryEvents) {
            EventEnvelope received = codec.fromReplicationEvent(codec.toReplicationEvent(sent));
            tx.executeWithoutResult(status -> {
                nodeState.lockForWrite();
                applier.apply(received, NOW);
                nodeState.markApplied(received.seq());
            });
        }

        assertEquals(6L, stock("P-01"));
        assertEquals(3, nodeState.read().lastAppliedSeq());
        assertEquals(0, nodeState.read().lastIssuedSeq());
        for (EventEnvelope sent : primaryEvents) {
            assertStoredRequest(sent, codec.requestPayload(sent));
            assertTrue(jdbc.queryForObject("SELECT response_payload = CAST(? AS jsonb) FROM processed_requests "
                    + "WHERE request_id = ?", Boolean.class, codec.responsePayload(sent), sent.requestId()));
        }
        assertStoredRequest(primaryEvents.get(1), codec.commandRequestPayload(EventType.STOCK_IN, "P-01", null, null, 10));
    }

    @Test void stockOutBeyondStockWritesNothing() {
        writeOnPrimary((epoch, seq) -> new ProductCreated("P-01", "Laptop", "pcs"));
        writeOnPrimary((epoch, seq) -> new StockIn("P-01", 5, EventCodec.voucherNo(EventType.STOCK_IN, epoch, seq)));

        assertThrows(IllegalStateException.class, () -> writeOnPrimary((epoch, seq) ->
                new StockOut("P-01", 6, EventCodec.voucherNo(EventType.STOCK_OUT, epoch, seq))));

        assertEquals(5L, stock("P-01"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stock_out", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operation_log", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM processed_requests", Long.class));
        assertEquals(2, nodeState.read().lastIssuedSeq());
    }

    @Test void eventsThatDoNotFitTheDataAreRejected() {
        assertThrows(IllegalStateException.class, () -> writeOnPrimary((epoch, seq) ->
                new StockIn("P-404", 1, EventCodec.voucherNo(EventType.STOCK_IN, epoch, seq))));
        assertThrows(IllegalStateException.class, () -> writeOnPrimary((epoch, seq) ->
                new StockOut("P-404", 1, EventCodec.voucherNo(EventType.STOCK_OUT, epoch, seq))));
        writeOnPrimary((epoch, seq) -> new ProductCreated("P-01", "Laptop", "pcs"));
        assertThrows(IllegalStateException.class, () -> writeOnPrimary((epoch, seq) ->
                new ProductCreated("P-01", "Other", "box")));

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_log", Long.class));
        assertEquals("Laptop", jdbc.queryForObject("SELECT product_name FROM products", String.class));
        assertEquals(1, nodeState.read().lastIssuedSeq());
    }

    @Test void policyChangeUpdatesOnlyItsParameterAndVersion() {
        EventEnvelope change = writeOnPrimary((epoch, seq) -> new PolicyChanged(PolicyParameter.BATCH_SIZE, 7, 2));

        Map<String, Object> policy = jdbc.queryForMap("SELECT delay_seconds, batch_size, config_version FROM replication_policy");
        assertEquals(5, policy.get("delay_seconds"));
        assertEquals(7, policy.get("batch_size"));
        assertEquals(2L, policy.get("config_version"));
        assertStoredRequest(change, codec.policyRequestPayload(PolicyParameter.BATCH_SIZE, 7));
    }

    @Test void epochStartRecordsEpochHistoryWithoutRequest() {
        jdbc.update("UPDATE node_state SET epoch = 2, last_issued_seq = 4, last_applied_seq = 4 WHERE singleton = TRUE");

        writeOnPrimary((epoch, seq) -> new EpochStarted("S2", "S2"));

        Map<String, Object> history = jdbc.queryForMap("SELECT epoch, start_seq, node_id, s4_source_node FROM epoch_history");
        assertEquals(2L, history.get("epoch"));
        assertEquals(5L, history.get("start_seq"));
        assertEquals("S2", history.get("node_id"));
        assertEquals("S2", history.get("s4_source_node"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM processed_requests", Long.class));
    }

    /** Builds a payload that may depend on the issued position (voucher numbers). */
    private interface PayloadAt {
        EventPayload at(long epoch, long seq);
    }

    /** Primary write path from docs/thiet-ke.md section 4: lock, issue seq, apply, commit. */
    private EventEnvelope writeOnPrimary(PayloadAt payload) {
        List<EventEnvelope> written = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            nodeState.lockForWrite();
            IssuedSeq issued = nodeState.issueNextSeq();
            EventPayload content = payload.at(issued.epoch(), issued.seq());
            UUID requestId = content.type() == EventType.EPOCH_START ? null : UUID.randomUUID();
            EventEnvelope event = codec.envelope(UUID.randomUUID(), issued.epoch(), issued.seq(), requestId, content);
            applier.apply(event, NOW);
            written.add(event);
        });
        return written.get(0);
    }

    private long stock(String productCode) {
        return jdbc.queryForObject("SELECT quantity FROM inventory WHERE product_code = ?", Long.class, productCode);
    }

    private void assertStoredRequest(EventEnvelope event, String expectedRequestPayload) {
        assertTrue(jdbc.queryForObject("""
                SELECT request_payload = CAST(? AS jsonb) AND event_id = ?
                  FROM processed_requests WHERE request_id = ?""",
                Boolean.class, expectedRequestPayload, event.eventId(), event.requestId()), event::toString);
    }
}
