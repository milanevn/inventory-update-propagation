package edu.inventory.node.grpc;

import edu.inventory.node.config.NodeProperties;
import edu.inventory.node.core.NodeStateRepository;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts one gRPC server per node and registers EVERY Spring bean that implements
 * {@link BindableService} (i.e. every class extending a generated {@code *ImplBase} and annotated
 * with {@code @Service}/{@code @Component}). Adding a new RPC service never requires editing this class.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final List<BindableService> services;
    private final NodeProperties properties;
    private final NodeStateRepository nodeState;
    private volatile boolean running;
    private Server server;

    public GrpcServerLifecycle(List<BindableService> services, NodeProperties properties, NodeStateRepository nodeState) {
        this.services = List.copyOf(services);
        this.properties = properties;
        this.nodeState = nodeState;
    }

    @Override public synchronized void start() {
        if (running) return;
        NettyServerBuilder builder = NettyServerBuilder.forPort(properties.grpcPort());
        Set<String> names = new HashSet<>();
        for (BindableService service : services) {
            ServerServiceDefinition definition = service.bindService();
            String name = definition.getServiceDescriptor().getName();
            if (!names.add(name)) {
                throw new IllegalStateException("gRPC service registered twice: " + name
                        + " (two beans implement the same *ImplBase)");
            }
            builder.addService(definition);
        }
        try {
            server = builder.build().start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot bind gRPC port " + properties.grpcPort()
                    + " for " + properties.id() + " (port already in use? run scripts\\doctor.ps1)", e);
        }
        running = true;
        startKeepAliveThread(server);
        log.info("{} [{}] gRPC listening on {} with services {}",
                properties.id(), readDurableRole(), properties.grpcPort(), names.stream().sorted().toList());
    }

    private String readDurableRole() {
        try {
            String role = nodeState.read().role();
            return role == null || role.isBlank() ? "UNASSIGNED" : role;
        } catch (Exception e) {
            log.warn("{} could not read durable role from node_state ({})", properties.id(), e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * grpc-netty-shaded event-loop threads are daemon threads and this is a non-web Spring Boot app,
     * so without a non-daemon thread the JVM exits as soon as main() returns. This thread ties the
     * process lifetime to the gRPC server: it ends only when the server has terminated.
     */
    private static void startKeepAliveThread(Server grpcServer) {
        Thread awaitThread = new Thread(() -> {
            try {
                grpcServer.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "grpc-server-await");
        awaitThread.setDaemon(false);
        awaitThread.start();
    }

    @Override public synchronized void stop() {
        if (!running) return;
        server.shutdown();
        try {
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow();
                server.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
        running = false;
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
