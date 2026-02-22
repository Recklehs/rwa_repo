package io.rwa.flink.model;

import java.io.Serializable;

public enum EventType implements Serializable {
    MARKET_LISTED,
    MARKET_BOUGHT,
    MARKET_CANCELLED,
    ERC1155_TRANSFER_SINGLE,
    ERC1155_TRANSFER_BATCH
}
