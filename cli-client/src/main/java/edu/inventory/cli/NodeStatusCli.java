package edu.inventory.cli;

import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusReply;
import edu.inventory.contracts.v1.NodeStatusRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Run: java -jar cli-client/target/cli-client.jar localhost:9101 [localhost:9102 ...]
 * Exit code 0 only if every target answered.
 */
public final class NodeStatusCli {
    private NodeStatusCli() { }

    public static void main(String[] args) throws Exception {
        String[] targets = args.length > 0 ? args : new String[] {"localhost:9101"};
        List<TargetStatus> statuses = new ArrayList<>(targets.length);
        int failures = 0;
        for (String target : targets) {
            TargetStatus status = fetchStatus(target);
            if (!status.isSuccess()) {
                failures++;
            }
            statuses.add(status);
        }
        Optional<NodeStatusReply> primary = selectPrimary(statuses);
        for (TargetStatus status : statuses) {
            System.out.println(formatLine(status, primary));
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    static Optional<NodeStatusReply> selectPrimary(List<TargetStatus> statuses) {
        return statuses.stream()
                .map(TargetStatus::reply)
                .filter(Objects::nonNull)
                .filter(reply -> "PRIMARY".equals(reply.getRole()))
                .max(Comparator.comparingLong(NodeStatusReply::getEpoch)
                        .thenComparingLong(NodeStatusReply::getLastIssuedSeq));
    }

    static String formatLine(TargetStatus status, Optional<NodeStatusReply> primary) {
        if (!status.isSuccess()) {
            return "%s -> %s".formatted(status.target(), status.error());
        }
        NodeStatusReply reply = status.reply();
        String role = reply.getRole().isEmpty() ? "-" : reply.getRole();
        String nodeStatus = reply.getStatus().isEmpty() ? "-" : reply.getStatus();
        String lag = primary
                .map(p -> Long.toString(p.getLastIssuedSeq() - reply.getLastAppliedSeq()))
                .orElse("n/a");
        return "%s -> node=%s role=%s epoch=%d issued=%d applied=%d lag=%s status=%s".formatted(
                status.target(), reply.getNodeId(), role, reply.getEpoch(),
                reply.getLastIssuedSeq(), reply.getLastAppliedSeq(), lag, nodeStatus);
    }

    private static TargetStatus fetchStatus(String target) throws InterruptedException {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build(); // local LAN only
        try {
            NodeStatusReply reply = NodeServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getStatus(NodeStatusRequest.getDefaultInstance());
            return TargetStatus.success(target, reply);
        } catch (StatusRuntimeException e) {
            String desc = e.getStatus().getDescription() == null ? "" : " " + e.getStatus().getDescription();
            return TargetStatus.failure(target, e.getStatus().getCode() + desc);
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    record TargetStatus(String target, NodeStatusReply reply, String error) {
        static TargetStatus success(String target, NodeStatusReply reply) {
            return new TargetStatus(target, Objects.requireNonNull(reply), null);
        }

        static TargetStatus failure(String target, String error) {
            return new TargetStatus(target, null, Objects.requireNonNull(error));
        }

        boolean isSuccess() {
            return reply != null;
        }
    }
}
