package edu.inventory.cli;

import edu.inventory.contracts.v1.NodeServiceGrpc;
import edu.inventory.contracts.v1.NodeStatusRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

/** Run: mvn -pl cli-client exec:java -Dexec.mainClass=edu.inventory.cli.NodeStatusCli -Dexec.args=localhost:9101 */
public final class NodeStatusCli {
    private NodeStatusCli() { }
    public static void main(String[] args) throws Exception {
        String target = args.length > 0 ? args[0] : "localhost:9101";
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build(); // local LAN only
        try {
            var reply = NodeServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getStatus(NodeStatusRequest.getDefaultInstance());
            System.out.printf("node=%s role=%s epoch=%d issued=%d applied=%d status=%s%n",
                    reply.getNodeId(), reply.getRole(), reply.getEpoch(),
                    reply.getLastIssuedSeq(), reply.getLastAppliedSeq(), reply.getStatus());
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
