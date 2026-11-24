package edu.inventory.contracts;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import edu.inventory.contracts.v1.CommandReply;
import edu.inventory.contracts.v1.CommandRequest;
import edu.inventory.contracts.v1.EventBatch;
import edu.inventory.contracts.v1.FetchReply;
import edu.inventory.contracts.v1.Inventory;
import edu.inventory.contracts.v1.OperationType;
import edu.inventory.contracts.v1.PolicyType;
import edu.inventory.contracts.v1.PolicyUpdateRequest;
import edu.inventory.contracts.v1.PromoteReply;
import edu.inventory.contracts.v1.PromoteRequest;
import edu.inventory.contracts.v1.ReplicationEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T18: the generated service and message descriptors match docs/thiet-ke.md, section 5. */
class ProtoContractTest {

    @Test void servicesExposeTheDocumentedRpcs() {
        assertEquals(Set.of("ExecuteCommand", "GetInventory", "ListInventory"), methods("InventoryService"));
        assertEquals(Set.of("ApplyEvents", "FetchEvents"), methods("ReplicationService"));
        assertEquals(Set.of("GetStatus", "UpdatePolicy", "Promote"), methods("NodeService"));
    }

    @Test void packageStaysInventoryV1() {
        assertEquals("inventory.v1", Inventory.getDescriptor().getPackage());
    }

    @Test void operationTypesIncludeSystemEvents() {
        assertEquals(4, OperationType.POLICY_CHANGE.getNumber());
        assertEquals(5, OperationType.EPOCH_START.getNumber());
        assertEquals(1, PolicyType.DELAY_SECONDS.getNumber());
        assertEquals(2, PolicyType.BATCH_SIZE.getNumber());
    }

    @Test void replicationMessagesCarrySenderEpochAndRequestId() {
        assertField(EventBatch.getDescriptor(), "epoch", 4, FieldDescriptor.Type.INT64);
        assertField(ReplicationEvent.getDescriptor(), "request_id", 11, FieldDescriptor.Type.STRING);
        assertField(ReplicationEvent.getDescriptor(), "schema_version", 12, FieldDescriptor.Type.INT32);
        assertField(ReplicationEvent.getDescriptor(), "policy_change", 13, FieldDescriptor.Type.MESSAGE);
        assertField(ReplicationEvent.getDescriptor(), "epoch_start", 14, FieldDescriptor.Type.MESSAGE);
    }

    @Test void voucherNumberIsGeneratedByTheServer() {
        assertField(CommandReply.getDescriptor(), "voucher_no", 6, FieldDescriptor.Type.STRING);
        assertReserved(CommandRequest.getDescriptor(), "voucher_no", 6);
    }

    @Test void policyUpdateUsesPolicyTypeInsteadOfRoute() {
        assertField(PolicyUpdateRequest.getDescriptor(), "policy_type", 4, FieldDescriptor.Type.ENUM);
        assertReserved(PolicyUpdateRequest.getDescriptor(), "route_id", 1);
    }

    @Test void removedFieldsAreReserved() {
        assertReserved(ReplicationEvent.getDescriptor(), "event_hash", 10);
        assertReserved(FetchReply.getDescriptor(), "snapshot_required", 2);
    }

    @Test void promoteTakesExpectedEpochAndReportsTheNewEpoch() {
        assertField(PromoteRequest.getDescriptor(), "expected_epoch", 1, FieldDescriptor.Type.INT64);
        assertField(PromoteReply.getDescriptor(), "new_epoch", 2, FieldDescriptor.Type.INT64);
        assertField(PromoteReply.getDescriptor(), "start_seq", 3, FieldDescriptor.Type.INT64);
        assertField(PromoteReply.getDescriptor(), "s4_source_node_id", 4, FieldDescriptor.Type.STRING);
    }

    private static Set<String> methods(String service) {
        ServiceDescriptor descriptor = Inventory.getDescriptor().findServiceByName(service);
        assertNotNull(descriptor, service);
        return descriptor.getMethods().stream().map(MethodDescriptor::getName).collect(Collectors.toSet());
    }

    private static void assertField(Descriptor message, String name, int number, FieldDescriptor.Type type) {
        FieldDescriptor field = message.findFieldByName(name);
        assertNotNull(field, message.getName() + "." + name);
        assertEquals(number, field.getNumber(), message.getName() + "." + name);
        assertEquals(type, field.getType(), message.getName() + "." + name);
    }

    private static void assertReserved(Descriptor message, String name, int number) {
        assertNull(message.findFieldByName(name), message.getName() + "." + name + " must be removed");
        DescriptorProto proto = message.toProto();
        assertTrue(proto.getReservedNameList().contains(name), message.getName() + " reserves name " + name);
        List<DescriptorProto.ReservedRange> ranges = proto.getReservedRangeList();
        assertTrue(ranges.stream().anyMatch(r -> r.getStart() <= number && number < r.getEnd()),
                message.getName() + " reserves number " + number);
    }
}
