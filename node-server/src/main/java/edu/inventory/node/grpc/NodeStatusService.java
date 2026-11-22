package edu.inventory.node.grpc;

import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusReply;
import edu.inventory.contracts.v1.NodeStatusRequest;
import edu.inventory.node.config.NodeProperties;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/** NodeService.GetStatus. UpdatePolicy is not implemented yet (returns UNIMPLEMENTED). */
@Service
public class NodeStatusService extends NodeServiceGrpc.NodeServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(NodeStatusService.class);

    private final NodeProperties properties;
    private final JdbcTemplate jdbc;

    public NodeStatusService(NodeProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public void getStatus(NodeStatusRequest request, StreamObserver<NodeStatusReply> responseObserver) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT epoch, last_issued_seq, last_applied_seq FROM node_state WHERE singleton = TRUE");
            NodeStatusReply reply = NodeStatusReply.newBuilder()
                    .setNodeId(properties.id())
                    .setRole(properties.role())
                    .setEpoch(((Number) row.get("epoch")).longValue())
                    .setLastIssuedSeq(((Number) row.get("last_issued_seq")).longValue())
                    .setLastAppliedSeq(((Number) row.get("last_applied_seq")).longValue())
                    .setStatus("READY")
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        } catch (Exception error) {
            log.warn("GetStatus failed: node database is unavailable ({})", error.getMessage());
            responseObserver.onError(Status.UNAVAILABLE.withDescription("node database is unavailable").asRuntimeException());
        }
    }
}
