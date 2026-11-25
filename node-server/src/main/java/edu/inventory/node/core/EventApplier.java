package edu.inventory.node.core;

import edu.inventory.domain.EventEnvelope;
import edu.inventory.node.core.EventPayload.EpochStarted;
import edu.inventory.node.core.EventPayload.PolicyChanged;
import edu.inventory.node.core.EventPayload.ProductCreated;
import edu.inventory.node.core.EventPayload.StockIn;
import edu.inventory.node.core.EventPayload.StockOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Applies one committed event to this node in the caller's transaction: its effect on the business
 * tables, {@code replication_policy} or {@code epoch_history}, its {@code operation_log} row, and the
 * {@code processed_requests} row of the request that produced it. The primary (after issuing the seq),
 * replicas and replay all call it, so every node ends up with the same rows for the same event.
 *
 * <p>It does not touch the {@code node_state} counters: the primary issues the seq with
 * {@link NodeStateRepository#issueNextSeq()} before, a replica calls
 * {@link NodeStateRepository#markApplied(long)} after. It does not decide whether an event may be
 * applied either (role, epoch, seq order, duplicates); callers check that first.
 *
 * <p>Only pass events already checked against the data: on the primary, reject a command (product
 * missing or already present, not enough stock) under the lock before issuing a seq, as in
 * docs/thiet-ke.md section 4. An exception from {@link #apply} marks the caller's transaction
 * rollback-only, so it cannot be used to detect a rejected command and then store its result in the
 * same transaction.
 */
@Component
public class EventApplier {
    private static final Logger log = LoggerFactory.getLogger(EventApplier.class);

    private final JdbcTemplate jdbc;
    private final OperationLogRepository operationLog;
    private final EventCodec codec;

    public EventApplier(JdbcTemplate jdbc, OperationLogRepository operationLog, EventCodec codec) {
        this.jdbc = jdbc;
        this.operationLog = operationLog;
        this.codec = codec;
    }

    /**
     * @param committedAt this node's clock when writing the event (primary: commit; replica: apply)
     * @throws IllegalStateException if the event cannot apply to the current data (product missing or
     *         already present, not enough stock); nothing is written and the caller's transaction should
     *         roll back. On a replica this means its data diverged from the source.
     * @throws IllegalArgumentException if the payload is malformed
     * @throws org.springframework.dao.DuplicateKeyException if the seq, event, voucher or request is
     *         already stored
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(EventEnvelope event, Instant committedAt) {
        switch (codec.payload(event)) {
            case ProductCreated p -> createProduct(p);
            case StockIn p -> stockIn(p);
            case StockOut p -> stockOut(p);
            case PolicyChanged p -> changePolicy(p);
            case EpochStarted p -> startEpoch(event, p);
        }
        operationLog.append(event, committedAt);
        String request = codec.requestPayload(event);
        if (request != null) {
            jdbc.update("""
                    INSERT INTO processed_requests (request_id, event_id, request_payload, response_payload)
                    VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb))""",
                    event.requestId(), event.eventId(), request, codec.responsePayload(event));
        }
        log.debug("applied seq={} event_id={} type={} epoch={} request_id={}",
                event.seq(), event.eventId(), event.type(), event.epoch(), event.requestId());
    }

    private void createProduct(ProductCreated p) {
        int created = jdbc.update("""
                INSERT INTO products (product_code, product_name, unit) VALUES (?, ?, ?)
                ON CONFLICT (product_code) DO NOTHING""", p.productCode(), p.productName(), p.unit());
        if (created != 1) throw new IllegalStateException("Product already exists: " + p.productCode());
        jdbc.update("INSERT INTO inventory (product_code, quantity) VALUES (?, 0)", p.productCode());
    }

    private void stockIn(StockIn p) {
        int updated = jdbc.update("""
                UPDATE inventory SET quantity = quantity + ?, updated_at = CURRENT_TIMESTAMP
                 WHERE product_code = ?""", p.quantity(), p.productCode());
        if (updated != 1) throw new IllegalStateException("Product not found: " + p.productCode());
        jdbc.update("INSERT INTO stock_in (voucher_no, product_code, quantity) VALUES (?, ?, ?)",
                p.voucherNo(), p.productCode(), p.quantity());
    }

    /** The conditional update keeps stock non-negative even if a caller skipped its own check (FR-01). */
    private void stockOut(StockOut p) {
        int updated = jdbc.update("""
                UPDATE inventory SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP
                 WHERE product_code = ? AND quantity >= ?""", p.quantity(), p.productCode(), p.quantity());
        if (updated != 1)
            throw new IllegalStateException("Product not found or not enough stock: " + p.productCode());
        jdbc.update("INSERT INTO stock_out (voucher_no, product_code, quantity) VALUES (?, ?, ?)",
                p.voucherNo(), p.productCode(), p.quantity());
    }

    private void changePolicy(PolicyChanged p) {
        String column = switch (p.parameter()) {
            case DELAY_SECONDS -> "delay_seconds";
            case BATCH_SIZE -> "batch_size";
        };
        jdbc.update("UPDATE replication_policy SET " + column + " = ?, config_version = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE singleton = TRUE", p.newValue(), p.configurationVersion());
    }

    private void startEpoch(EventEnvelope event, EpochStarted p) {
        jdbc.update("INSERT INTO epoch_history (epoch, start_seq, node_id, s4_source_node) VALUES (?, ?, ?, ?)",
                event.epoch(), event.seq(), p.primaryNodeId(), p.s4SourceNodeId());
    }
}
