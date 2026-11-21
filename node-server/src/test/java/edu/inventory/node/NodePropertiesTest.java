package edu.inventory.node;

import edu.inventory.node.config.NodeProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodePropertiesTest {
    @Test void acceptsKnownRoles() {
        assertEquals("PRIMARY", new NodeProperties("S1", "PRIMARY", 9101).role());
    }
    @Test void rejectsInvalidPortAndRole() {
        assertThrows(IllegalArgumentException.class, () -> new NodeProperties("S2", "REPLICA", -1));
        assertThrows(IllegalArgumentException.class, () -> new NodeProperties("S2", "WRITER", 9102));
    }
}
