package edu.inventory.node;

import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusReply;
import edu.inventory.contracts.v1.NodeStatusRequest;
import edu.inventory.node.support.AbstractNodeIT;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Example integration test: real Spring context + real PostgreSQL + real gRPC call. Copy this pattern. */
class NodeStatusIT extends AbstractNodeIT {

    @Autowired JdbcTemplate jdbc;

    @AfterEach void restoreNodeState() {
        jdbc.update("UPDATE node_state SET role = 'PRIMARY', status = 'READY' WHERE singleton = TRUE");
    }

    @Test void flywayCreatesTheV3SchemaWithOneNodeStateRow() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("products", "stock_in", "stock_out", "inventory", "node_state",
                "operation_log", "replication_progress", "processed_requests",
                "replication_policy", "epoch_history")), tables::toString);
        for (String dropped : List.of("snapshot_metadata", "outbox", "replication_batch")) {
            assertFalse(tables.contains(dropped), tables::toString);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM node_state", Integer.class));
    }

    @Test void operationLogRequiresCommittedAtAndProgressHasRetryState() {
        assertEquals("NO", columnNullability("operation_log", "committed_at"));
        assertEquals("NO", columnNullability("replication_progress", "retry_count"));
        assertEquals("YES", columnNullability("replication_progress", "next_retry_at"));
        assertEquals("YES", columnNullability("replication_progress", "last_error"));
        assertEquals(List.of("last_acked_seq", "last_error", "next_retry_at", "peer_id", "retry_count", "updated_at"),
                jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'replication_progress' ORDER BY column_name", String.class));
    }

    private String columnNullability(String table, String column) {
        return jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?", String.class, table, column);
    }

    @Test void configuredRoleIsStoredInNodeStateOnFirstStart() {
        Map<String, Object> row = jdbc.queryForMap("SELECT role, status FROM node_state WHERE singleton = TRUE");
        assertEquals("PRIMARY", row.get("role"));
        assertEquals("READY", row.get("status"));
    }

    @Test void replicationPolicyHasDefaultsAndRejectsOutOfRangeValues() {
        Map<String, Object> policy = jdbc.queryForMap("SELECT delay_seconds, batch_size, config_version FROM replication_policy");
        assertEquals(5, policy.get("delay_seconds"));
        assertEquals(3, policy.get("batch_size"));
        assertEquals(1L, policy.get("config_version"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE replication_policy SET delay_seconds = 3601"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE replication_policy SET batch_size = 0"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE replication_policy SET batch_size = 101"));
    }

    @Test void getStatusAnswersOverGrpc() throws InterruptedException {
        NodeStatusReply reply = getStatus();
        assertEquals("S1", reply.getNodeId());
        assertEquals("PRIMARY", reply.getRole());
        assertEquals(1, reply.getEpoch());
        assertEquals("READY", reply.getStatus());
    }

    @Test void getStatusReportsRoleAndStatusFromNodeStateNotFromConfiguration() throws InterruptedException {
        jdbc.update("UPDATE node_state SET role = 'REPLICA', status = 'QUARANTINED' WHERE singleton = TRUE");

        NodeStatusReply reply = getStatus();

        assertEquals("REPLICA", reply.getRole());
        assertEquals("QUARANTINED", reply.getStatus());
    }

    private NodeStatusReply getStatus() throws InterruptedException {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT).usePlaintext().build();
        try {
            return NodeServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getStatus(NodeStatusRequest.getDefaultInstance());
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
