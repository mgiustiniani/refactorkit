package example.consumer;

import example.ownership.SharedValue;

public final class Consumer {
    private final SharedValue value = new SharedValue("accepted");
}
