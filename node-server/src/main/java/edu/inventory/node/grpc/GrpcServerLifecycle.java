package edu.inventory.node.grpc;

import edu.inventory.node.config.NodeProperties;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private final NodeStatusService nodeStatusService;
    private final NodeProperties properties;
    private volatile boolean running;
    private Server server;

    public GrpcServerLifecycle(NodeStatusService nodeStatusService, NodeProperties properties) {
        this.nodeStatusService = nodeStatusService;
        this.properties = properties;
    }

    @Override public synchronized void start() {
        if (running) return;
        try {
            server = NettyServerBuilder.forPort(properties.grpcPort())
                    .addService(nodeStatusService)
                    .build().start();
            running = true;
            System.out.printf("%s [%s] gRPC listening on %d%n", properties.id(), properties.role(), properties.grpcPort());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start gRPC server", e);
        }
    }

    @Override public synchronized void stop() {
        if (!running) return;
        server.shutdown();
        try { server.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        running = false;
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
