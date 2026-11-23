package edu.inventory.node.recovery;

import edu.inventory.node.config.NodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * On a fresh database, stores the configured role ({@code NODE_ROLE}) in {@code node_state}.
 * Afterwards {@code node_state.role} is the source of truth (promote and step-down change it there),
 * so a restarted node keeps its durable role even if the configuration says otherwise.
 * Runs during context initialisation, before the gRPC server starts.
 */
@Component
public class NodeRoleInitializer implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(NodeRoleInitializer.class);

    private final NodeProperties properties;
    private final NodeStateRepository repository;

    public NodeRoleInitializer(NodeProperties properties, NodeStateRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @Override
    public void afterPropertiesSet() {
        String stored = repository.initializeRoleIfMissing(properties.role());
        if (!stored.equals(properties.role())) {
            log.warn("{} configured role {} ignored: node_state says {}", properties.id(), properties.role(), stored);
        } else {
            log.info("{} role {}", properties.id(), stored);
        }
    }
}
