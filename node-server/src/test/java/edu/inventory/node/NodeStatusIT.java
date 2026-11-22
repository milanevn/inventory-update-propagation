package edu.inventory.node;

import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusReply;
import edu.inventory.contracts.v1.NodeStatusRequest;
import edu.inventory.node.support.AbstractNodeIT;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Example integration test: real Spring context + real PostgreSQL + real gRPC call. Copy this pattern. */
class NodeStatusIT extends AbstractNodeIT {

    @Autowired JdbcTemplate jdbc;

    @Test void flywayCreatesTheSchemaWithOneNodeStateRow() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("products", "inventory", "node_state", "operation_log", "outbox",
                "replication_progress", "processed_requests", "replication_policy", "snapshot_metadata")), tables::toString);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM node_state", Integer.class));
    }

    @Test void getStatusAnswersOverGrpc() throws InterruptedException {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT).usePlaintext().build();
        try {
            NodeStatusReply reply = NodeServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getStatus(NodeStatusRequest.getDefaultInstance());
            assertEquals("S1", reply.getNodeId());
            assertEquals("PRIMARY", reply.getRole());
            assertEquals(1, reply.getEpoch());
            assertEquals("READY", reply.getStatus());
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
