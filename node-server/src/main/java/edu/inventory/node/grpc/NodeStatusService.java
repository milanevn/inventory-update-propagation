package edu.inventory.node.grpc;

import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusReply;
import edu.inventory.contracts.v1.NodeStatusRequest;
import edu.inventory.node.config.NodeProperties;
import edu.inventory.node.recovery.NodeState;
import edu.inventory.node.recovery.NodeStateRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** NodeService.GetStatus. UpdatePolicy and Promote are not implemented yet (return UNIMPLEMENTED). */
@Service
public class NodeStatusService extends NodeServiceGrpc.NodeServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(NodeStatusService.class);

    private final NodeProperties properties;
    private final NodeStateRepository nodeState;

    public NodeStatusService(NodeProperties properties, NodeStateRepository nodeState) {
        this.properties = properties;
        this.nodeState = nodeState;
    }

    @Override
    public void getStatus(NodeStatusRequest request, StreamObserver<NodeStatusReply> responseObserver) {
        NodeState state;
        try {
            state = nodeState.read();
        } catch (Exception error) {
            log.warn("GetStatus failed: node database is unavailable ({})", error.getMessage());
            responseObserver.onError(Status.UNAVAILABLE.withDescription("node database is unavailable").asRuntimeException());
            return;
        }
        responseObserver.onNext(NodeStatusReply.newBuilder()
                .setNodeId(properties.id())
                .setRole(state.role())
                .setEpoch(state.epoch())
                .setLastIssuedSeq(state.lastIssuedSeq())
                .setLastAppliedSeq(state.lastAppliedSeq())
                .setStatus(state.status())
                .build());
        responseObserver.onCompleted();
    }
}
