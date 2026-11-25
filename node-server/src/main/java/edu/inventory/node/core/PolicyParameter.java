package edu.inventory.node.core;

/** The two independent replication policy parameters, with their allowed ranges (docs/thiet-ke.md, section 4). */
public enum PolicyParameter {
    /** S3 route delay in seconds. */
    DELAY_SECONDS(0, 3600),
    /** S4 route batch size in business events. */
    BATCH_SIZE(1, 100);

    private final int min;
    private final int max;

    PolicyParameter(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public boolean accepts(int value) {
        return value >= min && value <= max;
    }
}
