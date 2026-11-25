package edu.inventory.node.core;

import edu.inventory.domain.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Writes {@code operation_log}, the permanent event log that is also the send queue of every
 * destination (design decision 17). Shared by the primary, replicas and replay.
 */
@Repository
public class OperationLogRepository {
    private static final Logger log = LoggerFactory.getLogger(OperationLogRepository.class);

    private final JdbcTemplate jdbc;

    public OperationLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends one event in the caller's transaction. The payload is stored as given; its format is
     * defined per event type elsewhere.
     *
     * @param committedAt when this node writes the row, on this node's clock (primary: command commit;
     *                    replica: event apply), supplied by the caller from its {@code Clock}
     * @throws org.springframework.dao.DuplicateKeyException if the {@code seq} or {@code event_id} is
     *         already in the log
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(EventEnvelope event, Instant committedAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(committedAt, "committedAt");
        jdbc.update("""
                INSERT INTO operation_log
                  (event_id, epoch, seq, operation_type, payload, request_id, schema_version, committed_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)""",
                event.eventId(), event.epoch(), event.seq(), event.type().name(), event.payloadJson(),
                new SqlParameterValue(Types.OTHER, event.requestId()), event.schemaVersion(),
                OffsetDateTime.ofInstant(committedAt, ZoneOffset.UTC));
        log.debug("operation_log append seq={} event_id={} type={} epoch={}",
                event.seq(), event.eventId(), event.type(), event.epoch());
    }
}
