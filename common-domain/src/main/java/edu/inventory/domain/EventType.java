package edu.inventory.domain;

/** Event types stored in the operation log. Only business events count toward the S4 batch. */
public enum EventType {
    CREATE_PRODUCT(true),
    STOCK_IN(true),
    STOCK_OUT(true),
    POLICY_CHANGE(false),
    EPOCH_START(false);

    private final boolean business;

    EventType(boolean business) {
        this.business = business;
    }

    public boolean isBusiness() {
        return business;
    }
}
