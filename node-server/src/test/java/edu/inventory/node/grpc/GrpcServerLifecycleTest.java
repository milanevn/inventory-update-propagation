package edu.inventory.node.grpc;

import edu.inventory.contracts.v1.ApplyCode;
import edu.inventory.contracts.v1.ApplyReply;
import edu.inventory.contracts.v1.EventBatch;
import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusReply;
import edu.inventory.contracts.v1.NodeStatusRequest;
import edu.inventory.contracts.v1.ReplicationServiceGrpc;
import edu.inventory.node.config.NodeProperties;
import edu.inventory.node.core.NodeState;
import edu.inventory.node.core.NodeStateRepository;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class GrpcServerLifecycleTest {

    /** Regression: the node JVM exited right after startup because all gRPC threads were daemon threads. */
    @Test void keepsANonDaemonThreadAliveWhileTheServerRunsAndReleasesItOnStop() throws Exception {
        NodeProperties props = new NodeProperties("S1", "PRIMARY", freePort());
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                List.of(new FakeNodeService()), props, nodeState("PRIMARY"));

        lifecycle.start();
        try {
            assertTrue(lifecycle.isRunning());
            Thread keepAlive = findThread("grpc-server-await").orElseThrow(
                    () -> new AssertionError("no grpc-server-await thread: JVM would exit after main()"));
            assertFalse(keepAlive.isDaemon(), "keep-alive thread must be non-daemon");

            lifecycle.stop();
            keepAlive.join(5_000);
            assertFalse(keepAlive.isAlive(), "keep-alive thread must end once the gRPC server terminates");
            assertFalse(lifecycle.isRunning());
        } finally {
            lifecycle.stop();
        }
    }

    /** New RPC services (one per module owner) must be served without editing GrpcServerLifecycle. */
    @Test void servesEveryRegisteredService() throws Exception {
        int port = freePort();
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                List.of(new FakeNodeService(), new FakeReplicationService()),
                new NodeProperties("S2", "REPLICA", port),
                nodeState("REPLICA"));
        lifecycle.start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        try {
            assertEquals("fake", NodeServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getStatus(NodeStatusRequest.getDefaultInstance()).getNodeId());
            assertEquals(ApplyCode.APPLIED, ReplicationServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .applyEvents(EventBatch.getDefaultInstance()).getResult());
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            lifecycle.stop();
        }
    }

    @Test void logsDurableRoleFromNodeStateInsteadOfConfiguration(CapturedOutput output) throws Exception {
        int port = freePort();
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                List.of(new FakeNodeService()),
                new NodeProperties("S1", "PRIMARY", port),
                nodeState("REPLICA"));

        lifecycle.start();
        try {
            assertTrue(output.getOut().contains("S1 [REPLICA] gRPC listening on " + port), output::getOut);
        } finally {
            lifecycle.stop();
        }
    }

    @Test void startsEvenWhenNodeStateRoleIsNullOrUnreadable(CapturedOutput output) throws Exception {
        NodeStateRepository unreadable = mock(NodeStateRepository.class);
        when(unreadable.read()).thenThrow(new IllegalStateException("db down"));
        int port = freePort();
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                List.of(new FakeNodeService()),
                new NodeProperties("S1", "PRIMARY", port),
                unreadable);

        lifecycle.start();
        try {
            assertTrue(lifecycle.isRunning());
            assertTrue(output.getOut().contains("S1 [UNKNOWN] gRPC listening on " + port), output::getOut);
        } finally {
            lifecycle.stop();
        }
    }

    @Test void rejectsTwoBeansForTheSameService() throws Exception {
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                List.<BindableService>of(new FakeNodeService(), new FakeNodeService()),
                new NodeProperties("S1", "PRIMARY", freePort()),
                nodeState("PRIMARY"));
        IllegalStateException error = assertThrows(IllegalStateException.class, lifecycle::start);
        assertTrue(error.getMessage().contains("inventory.v1.NodeService"), error.getMessage());
        assertFalse(lifecycle.isRunning());
    }

    private static NodeStateRepository nodeState(String role) {
        NodeStateRepository repo = mock(NodeStateRepository.class);
        when(repo.read()).thenReturn(new NodeState(role, 1, 0, 0, "READY"));
        return repo;
    }

    private static final class FakeNodeService extends NodeServiceGrpc.NodeServiceImplBase {
        @Override public void getStatus(NodeStatusRequest request, StreamObserver<NodeStatusReply> observer) {
            observer.onNext(NodeStatusReply.newBuilder().setNodeId("fake").build());
            observer.onCompleted();
        }
    }

    private static final class FakeReplicationService extends ReplicationServiceGrpc.ReplicationServiceImplBase {
        @Override public void applyEvents(EventBatch request, StreamObserver<ApplyReply> observer) {
            observer.onNext(ApplyReply.newBuilder().setResult(ApplyCode.APPLIED).build());
            observer.onCompleted();
        }
    }

    private static Optional<Thread> findThread(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> name.equals(t.getName())).findFirst();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
