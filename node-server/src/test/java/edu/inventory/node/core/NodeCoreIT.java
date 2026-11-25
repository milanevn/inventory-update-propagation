package edu.inventory.node.core;

import edu.inventory.domain.EventEnvelope;
import edu.inventory.domain.EventType;
import edu.inventory.node.support.AbstractNodeIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Lock, sequence numbers and operation_log writes of the shared node core (T01, T02 groundwork). */
class NodeCoreIT extends AbstractNodeIT {

    private static final Instant COMMITTED_AT = Instant.parse("2026-11-25T10:00:00Z");
    /** PostgreSQL SQLSTATE for FOR UPDATE NOWAIT on a row locked by another transaction. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired JdbcTemplate jdbc;
    @Autowired NodeStateRepository nodeState;
    @Autowired OperationLogRepository operationLog;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach @AfterEach void resetLogAndProgress() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM processed_requests");
        jdbc.update("DELETE FROM operation_log");
        jdbc.update("UPDATE node_state SET role = 'PRIMARY', epoch = 1, last_issued_seq = 0, last_applied_seq = 0 "
                + "WHERE singleton = TRUE");
    }

    @Test void lockForWriteHoldsTheRowUntilTheTransactionEnds() {
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Runnable tryLockFromOtherConnection = () -> other.executeWithoutResult(status ->
                jdbc.queryForObject("SELECT last_issued_seq FROM node_state WHERE singleton = TRUE FOR UPDATE NOWAIT",
                        Long.class));

        tx.executeWithoutResult(status -> {
            nodeState.lockForWrite();
            // Locked before any seq is issued, so checks such as request deduplication run under the lock.
            DataAccessException blocked = assertThrows(DataAccessException.class, tryLockFromOtherConnection::run);
            assertEquals(LOCK_NOT_AVAILABLE, ((SQLException) blocked.getMostSpecificCause()).getSQLState());
        });

        assertDoesNotThrow(tryLockFromOtherConnection::run);
    }

    @Test void replicaCannotIssueSeqEvenWithEqualCounters() {
        jdbc.update("UPDATE node_state SET role = 'REPLICA' WHERE singleton = TRUE");

        assertThrows(IllegalStateException.class, () -> tx.execute(status -> nodeState.issueNextSeq()));
        NodeState state = nodeState.read();
        assertEquals(0, state.lastIssuedSeq());
        assertEquals(0, state.lastAppliedSeq());
    }

    @Test void issuedSeqCarriesTheEpochReadInTheSameStatement() {
        jdbc.update("UPDATE node_state SET epoch = 3 WHERE singleton = TRUE");

        IssuedSeq issued = tx.execute(status -> nodeState.issueNextSeq());

        assertEquals(new IssuedSeq(3, 1), issued);
    }

    @Test void primaryIssuesContiguousSeqAndAdvancesBothCounters() {
        assertEquals(1L, writeInTransaction());
        assertEquals(2L, writeInTransaction());

        NodeState state = nodeState.read();
        assertEquals(2, state.lastIssuedSeq());
        assertEquals(2, state.lastAppliedSeq());
        assertEquals(List.of(1L, 2L), jdbc.queryForList("SELECT seq FROM operation_log ORDER BY seq", Long.class));
    }

    @Test void rollbackAfterIssuingSeqLeavesNoGap() {
        writeInTransaction();
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            writeOnPrimary();
            throw new IllegalStateException("business rule failed after seq was issued");
        }));

        assertEquals(2L, writeInTransaction());
        assertEquals(List.of(1L, 2L), jdbc.queryForList("SELECT seq FROM operation_log ORDER BY seq", Long.class));
    }

    @Test void concurrentWritersGetDistinctContiguousSeq() throws Exception {
        int threads = 4;
        int writesPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<Long>>> writers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                writers.add(() -> {
                    List<Long> issued = new ArrayList<>();
                    for (int i = 0; i < writesPerThread; i++) {
                        issued.add(writeInTransaction());
                    }
                    return issued;
                });
            }
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> result : pool.invokeAll(writers)) {
                all.addAll(result.get());
            }
            all.sort(null);

            long total = (long) threads * writesPerThread;
            assertEquals(LongStream.rangeClosed(1, total).boxed().toList(), all);
            assertEquals(total, nodeState.read().lastIssuedSeq());
            assertEquals(total, jdbc.queryForObject("SELECT count(*) FROM operation_log", Long.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void appendRejectsSeqAlreadyInLog() {
        writeInTransaction();

        assertThrows(DuplicateKeyException.class, () -> tx.executeWithoutResult(status ->
                operationLog.append(event(1, EventType.STOCK_IN, UUID.randomUUID()), COMMITTED_AT)));
    }

    @Test void appendStoresCommittedAtPayloadAndNullRequestIdForEpochStart() {
        tx.executeWithoutResult(status -> {
            nodeState.lockForWrite();
            operationLog.append(event(nodeState.issueNextSeq().seq(), EventType.EPOCH_START, null), COMMITTED_AT);
        });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT operation_type, request_id, payload::text AS payload, committed_at FROM operation_log");
        assertEquals("EPOCH_START", row.get("operation_type"));
        assertNull(row.get("request_id"));
        assertEquals("{\"note\": \"test\"}", row.get("payload"));
        assertEquals(COMMITTED_AT, jdbc.queryForObject("SELECT committed_at FROM operation_log", OffsetDateTime.class)
                .toInstant());
    }

    @Test void replicaMarksOnlyTheNextContiguousSeqAndDoesNotIssue() {
        jdbc.update("UPDATE node_state SET role = 'REPLICA' WHERE singleton = TRUE");
        applyOnReplica(1);

        assertThrows(IllegalStateException.class, () -> applyOnReplica(3));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> nodeState.issueNextSeq()));
        NodeState state = nodeState.read();
        assertEquals(0, state.lastIssuedSeq());
        assertEquals(1, state.lastAppliedSeq());
        assertEquals(List.of(1L), jdbc.queryForList("SELECT seq FROM operation_log ORDER BY seq", Long.class));
    }

    @Test void replicaCannotMarkSeqMissingFromOperationLog() {
        jdbc.update("UPDATE node_state SET role = 'REPLICA' WHERE singleton = TRUE");

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            nodeState.lockForWrite();
            nodeState.markApplied(1);
        }));
        assertEquals(0, nodeState.read().lastAppliedSeq());
    }

    @Test void primaryCannotMarkApplied() {
        assertThrows(IllegalStateException.class, () -> applyOnReplica(1));

        NodeState state = nodeState.read();
        assertEquals(0, state.lastIssuedSeq());
        assertEquals(0, state.lastAppliedSeq());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_log", Long.class));
    }

    @Test void writeMethodsRequireTheCallersTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> nodeState.lockForWrite());
        assertThrows(IllegalTransactionStateException.class, () -> nodeState.issueNextSeq());
        assertThrows(IllegalTransactionStateException.class, () -> nodeState.markApplied(1));
        assertThrows(IllegalTransactionStateException.class,
                () -> operationLog.append(event(1, EventType.STOCK_IN, UUID.randomUUID()), COMMITTED_AT));
        assertEquals(0, nodeState.read().lastAppliedSeq());
    }

    /** The replica apply order from docs/thiet-ke.md section 4: lock, append the received event, mark applied. */
    private void applyOnReplica(long seq) {
        tx.executeWithoutResult(status -> {
            nodeState.lockForWrite();
            operationLog.append(event(seq, EventType.CREATE_PRODUCT, UUID.randomUUID()), COMMITTED_AT);
            nodeState.markApplied(seq);
        });
    }

    private long writeInTransaction() {
        Long seq = tx.execute(status -> writeOnPrimary());
        return Objects.requireNonNull(seq);
    }

    /** The primary write order from docs/thiet-ke.md section 4: lock, issue seq, append, commit. */
    private long writeOnPrimary() {
        nodeState.lockForWrite();
        IssuedSeq issued = nodeState.issueNextSeq();
        operationLog.append(new EventEnvelope(UUID.randomUUID(), issued.epoch(), issued.seq(), EventType.STOCK_IN,
                UUID.randomUUID(), 1, "{\"note\": \"test\"}"), COMMITTED_AT);
        return issued.seq();
    }

    private static EventEnvelope event(long seq, EventType type, UUID requestId) {
        return new EventEnvelope(UUID.randomUUID(), 1, seq, type, requestId, 1, "{\"note\": \"test\"}");
    }
}
