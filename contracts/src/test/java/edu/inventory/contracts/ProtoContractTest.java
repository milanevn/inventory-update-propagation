package edu.inventory.contracts;

import edu.inventory.contracts.v1.InventoryServiceGrpc;
import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.ReplicationServiceGrpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtoContractTest {
    @Test void servicesArePartOfTheWireContract() {
        assertEquals(2, InventoryServiceGrpc.getServiceDescriptor().getMethods().size());
        assertEquals(2, ReplicationServiceGrpc.getServiceDescriptor().getMethods().size());
        assertEquals(2, NodeServiceGrpc.getServiceDescriptor().getMethods().size());
    }
}
