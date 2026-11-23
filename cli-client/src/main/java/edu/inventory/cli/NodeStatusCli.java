package edu.inventory.cli;

import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;

/**
 * Run: java -jar cli-client/target/cli-client.jar localhost:9101 [localhost:9102 ...]
 * Exit code 0 only if every target answered.
 */
public final class NodeStatusCli {
    private NodeStatusCli() { }
    public static void main(String[] args) throws Exception {
        String[] targets = args.length > 0 ? args : new String[] {"localhost:9101"};
        int failures = 0;
        for (String target : targets) {
            if (!printStatus(target)) failures++;
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean printStatus(String target) throws InterruptedException {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build(); // local LAN only
        try {
            var reply = NodeServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getStatus(NodeStatusRequest.getDefaultInstance());
            System.out.printf("%s -> node=%s role=%s epoch=%d issued=%d applied=%d status=%s%n",
                    target, reply.getNodeId(), reply.getRole(), reply.getEpoch(),
                    reply.getLastIssuedSeq(), reply.getLastAppliedSeq(), reply.getStatus());
            return true;
        } catch (StatusRuntimeException e) {
            System.out.printf("%s -> %s %s%n", target, e.getStatus().getCode(),
                    e.getStatus().getDescription() == null ? "" : e.getStatus().getDescription());
            return false;
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
