package com.colonyautopilot.bridge.runtime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class BridgeMetrics {
    private final LongAdder requests = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder actions = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final AtomicLong lastPollNanos = new AtomicLong();
    private final AtomicLong maximumPollNanos = new AtomicLong();

    public void recordPoll(long nanos, int requestCount) {
        lastPollNanos.set(nanos);
        maximumPollNanos.accumulateAndGet(nanos, Math::max);
        requests.add(requestCount);
    }

    public void recordRejected() {
        rejected.increment();
    }

    public void recordAction() {
        actions.increment();
    }

    public void recordFailure() {
        failures.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
            requests.sum(),
            rejected.sum(),
            actions.sum(),
            failures.sum(),
            lastPollNanos.get(),
            maximumPollNanos.get()
        );
    }

    public record Snapshot(
        long requests,
        long rejected,
        long actions,
        long failures,
        long lastPollNanos,
        long maximumPollNanos
    ) {
    }
}
