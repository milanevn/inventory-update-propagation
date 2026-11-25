package edu.inventory.node.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.inventory.contracts.v1.EpochStart;
import edu.inventory.contracts.v1.OperationType;
import edu.inventory.contracts.v1.PolicyChange;
import edu.inventory.contracts.v1.PolicyType;
import edu.inventory.contracts.v1.ReplicationEvent;
import edu.inventory.domain.EventEnvelope;
import edu.inventory.domain.EventType;
import edu.inventory.node.core.EventPayload.EpochStarted;
import edu.inventory.node.core.EventPayload.PolicyChanged;
import edu.inventory.node.core.EventPayload.ProductCreated;
import edu.inventory.node.core.EventPayload.StockIn;
import edu.inventory.node.core.EventPayload.StockOut;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single conversion point between events, their JSON columns and {@link ReplicationEvent}
 * (format in docs/thiet-ke.md section 3). The primary, replicas and replay all go through it, so
 * a replica rebuilds exactly the {@code request_payload} and {@code response_payload} the primary
 * stored, and request deduplication still works after a promote (T23).
 *
 * <p>Uses its own fixed {@link JsonMapper} rather than the application's, so global Jackson settings
 * cannot change what is written to the log.
 */
@Component
public class EventCodec {

    public static final int SCHEMA_VERSION = 1;

    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    /** Builds a new event with a payload in the current schema version. */
    public EventEnvelope envelope(UUID eventId, long epoch, long seq, UUID requestId, EventPayload payload) {
        if (payload.type() == EventType.EPOCH_START ? requestId != null : requestId == null)
            throw new IllegalArgumentException("requestId must be null exactly for EPOCH_START: " + payload.type());
        return new EventEnvelope(eventId, epoch, seq, payload.type(), requestId, SCHEMA_VERSION, write(payload));
    }

    /** Parses the payload of a stored or received event. */
    public EventPayload payload(EventEnvelope event) {
        if (event.schemaVersion() != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported schema_version " + event.schemaVersion());
        Class<? extends EventPayload> type = switch (event.type()) {
            case CREATE_PRODUCT -> ProductCreated.class;
            case STOCK_IN -> StockIn.class;
            case STOCK_OUT -> StockOut.class;
            case POLICY_CHANGE -> PolicyChanged.class;
            case EPOCH_START -> EpochStarted.class;
        };
        try {
            return json.readValue(event.payloadJson(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid " + event.type() + " payload at seq " + event.seq(), e);
        }
    }

    public static String voucherNo(EventType type, long epoch, long seq) {
        return switch (type) {
            case STOCK_IN -> "IN-" + epoch + "-" + seq;
            case STOCK_OUT -> "OUT-" + epoch + "-" + seq;
            default -> throw new IllegalArgumentException("No voucher for " + type);
        };
    }

    /** Event to wire format; {@code event_id}, {@code epoch} and {@code seq} are forwarded unchanged. */
    public ReplicationEvent toReplicationEvent(EventEnvelope event) {
        ReplicationEvent.Builder wire = ReplicationEvent.newBuilder()
                .setEventId(event.eventId().toString())
                .setEpoch(event.epoch())
                .setSeq(event.seq())
                .setOperation(OperationType.valueOf(event.type().name()))
                .setRequestId(event.requestId() == null ? "" : event.requestId().toString())
                .setSchemaVersion(event.schemaVersion());
        switch (payload(event)) {
            case ProductCreated p -> wire.setProductCode(p.productCode()).setProductName(p.productName()).setUnit(p.unit());
            case StockIn p -> wire.setProductCode(p.productCode()).setQuantity(p.quantity()).setVoucherNo(p.voucherNo());
            case StockOut p -> wire.setProductCode(p.productCode()).setQuantity(p.quantity()).setVoucherNo(p.voucherNo());
            case PolicyChanged p -> wire.setPolicyChange(PolicyChange.newBuilder()
                    .setPolicyType(PolicyType.valueOf(p.parameter().name()))
                    .setNewValue(p.newValue())
                    .setConfigurationVersion(p.configurationVersion()));
            case EpochStarted p -> wire.setEpochStart(EpochStart.newBuilder()
                    .setPrimaryNodeId(p.primaryNodeId())
                    .setS4SourceNodeId(p.s4SourceNodeId()));
        }
        return wire.build();
    }

    /**
     * Wire format to event. Fields that do not belong to the operation must be empty.
     *
     * @throws IllegalArgumentException if the event is malformed
     */
    public EventEnvelope fromReplicationEvent(ReplicationEvent wire) {
        EventType type = switch (wire.getOperation()) {
            case CREATE_PRODUCT -> EventType.CREATE_PRODUCT;
            case STOCK_IN -> EventType.STOCK_IN;
            case STOCK_OUT -> EventType.STOCK_OUT;
            case POLICY_CHANGE -> EventType.POLICY_CHANGE;
            case EPOCH_START -> EventType.EPOCH_START;
            default -> throw new IllegalArgumentException("Unknown operation " + wire.getOperation());
        };
        boolean product = type == EventType.CREATE_PRODUCT;
        boolean stock = type == EventType.STOCK_IN || type == EventType.STOCK_OUT;
        if ((!product && !stock && !wire.getProductCode().isEmpty())
                || (!product && (!wire.getProductName().isEmpty() || !wire.getUnit().isEmpty()))
                || (!stock && (wire.getQuantity() != 0 || !wire.getVoucherNo().isEmpty()))
                || (type != EventType.POLICY_CHANGE && wire.hasPolicyChange())
                || (type != EventType.EPOCH_START && wire.hasEpochStart()))
            throw new IllegalArgumentException("Fields not used by " + type + " are set at seq " + wire.getSeq());
        if (wire.getSchemaVersion() != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported schema_version " + wire.getSchemaVersion());
        EventPayload payload = switch (type) {
            case CREATE_PRODUCT -> new ProductCreated(wire.getProductCode(), wire.getProductName(), wire.getUnit());
            case STOCK_IN -> new StockIn(wire.getProductCode(), wire.getQuantity(), wire.getVoucherNo());
            case STOCK_OUT -> new StockOut(wire.getProductCode(), wire.getQuantity(), wire.getVoucherNo());
            case POLICY_CHANGE -> {
                if (!wire.hasPolicyChange()) throw new IllegalArgumentException("POLICY_CHANGE without policy_change");
                PolicyChange change = wire.getPolicyChange();
                yield new PolicyChanged(policyParameter(change.getPolicyType()), change.getNewValue(),
                        change.getConfigurationVersion());
            }
            case EPOCH_START -> {
                if (!wire.hasEpochStart()) throw new IllegalArgumentException("EPOCH_START without epoch_start");
                yield new EpochStarted(wire.getEpochStart().getPrimaryNodeId(), wire.getEpochStart().getS4SourceNodeId());
            }
        };
        UUID requestId = wire.getRequestId().isEmpty() ? null : uuid(wire.getRequestId(), "request_id");
        return envelope(uuid(wire.getEventId(), "event_id"), wire.getEpoch(), wire.getSeq(), requestId, payload);
    }

    /**
     * Canonical {@code processed_requests.request_payload} of a client command, used by the primary for
     * every command (also rejected ones) and compared on retry. Only the fields of the operation are kept.
     */
    public String commandRequestPayload(EventType operation, String productCode, String productName, String unit,
                                        long quantity) {
        return write(switch (operation) {
            case CREATE_PRODUCT -> new CommandRequestJson(operation.name(), productCode, productName, unit, null);
            case STOCK_IN, STOCK_OUT -> new CommandRequestJson(operation.name(), productCode, null, null, quantity);
            default -> throw new IllegalArgumentException("Not a client command: " + operation);
        });
    }

    /** Canonical {@code processed_requests.request_payload} of an {@code UpdatePolicy} request. */
    public String policyRequestPayload(PolicyParameter parameter, int newValue) {
        return write(new PolicyRequestJson(EventType.POLICY_CHANGE.name(), parameter, newValue));
    }

    /**
     * The {@code request_payload} the originating request had, rebuilt from the committed event; equal to
     * what the primary stored. {@code null} for {@code EPOCH_START}, which has no request.
     */
    public String requestPayload(EventEnvelope event) {
        return switch (payload(event)) {
            case ProductCreated p -> commandRequestPayload(event.type(), p.productCode(), p.productName(), p.unit(), 0);
            case StockIn p -> commandRequestPayload(event.type(), p.productCode(), null, null, p.quantity());
            case StockOut p -> commandRequestPayload(event.type(), p.productCode(), null, null, p.quantity());
            case PolicyChanged p -> policyRequestPayload(p.parameter(), p.newValue());
            case EpochStarted p -> null;
        };
    }

    /**
     * The {@code response_payload} of the request that produced a committed event; a retry with the same
     * request is answered from it. {@code null} for {@code EPOCH_START}.
     */
    public String responsePayload(EventEnvelope event) {
        return switch (payload(event)) {
            case ProductCreated p -> write(new CommittedCommandJson(true, event.eventId(), event.epoch(), event.seq(), null));
            case StockIn p -> write(new CommittedCommandJson(true, event.eventId(), event.epoch(), event.seq(), p.voucherNo()));
            case StockOut p -> write(new CommittedCommandJson(true, event.eventId(), event.epoch(), event.seq(), p.voucherNo()));
            case PolicyChanged p -> write(new PolicyResultJson(true, p.configurationVersion()));
            case EpochStarted p -> null;
        };
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + value, e);
        }
    }

    private static PolicyParameter policyParameter(PolicyType type) {
        return switch (type) {
            case DELAY_SECONDS -> PolicyParameter.DELAY_SECONDS;
            case BATCH_SIZE -> PolicyParameter.BATCH_SIZE;
            default -> throw new IllegalArgumentException("Unknown policy_type " + type);
        };
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not a UUID: " + value, e);
        }
    }

    private record CommandRequestJson(String operation, String productCode, String productName, String unit,
                                      Long quantity) {
    }

    private record PolicyRequestJson(String operation, PolicyParameter parameter, int newValue) {
    }

    private record CommittedCommandJson(boolean committed, UUID eventId, long epoch, long seq, String voucherNo) {
    }

    private record PolicyResultJson(boolean accepted, long configurationVersion) {
    }
}
