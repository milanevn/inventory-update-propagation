package edu.inventory.node.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "node")
public record NodeProperties(String id, String role, int grpcPort) {
    public NodeProperties {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("node.id is required");
        if (!"PRIMARY".equals(role) && !"REPLICA".equals(role))
            throw new IllegalArgumentException("node.role must be PRIMARY or REPLICA");
        if (grpcPort < 1 || grpcPort > 65535) throw new IllegalArgumentException("invalid grpc port");
    }
}
