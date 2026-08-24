package com.colonyautopilot.bridge.action;

import com.colonyautopilot.bridge.ipc.BridgeJsonCodec;
import com.colonyautopilot.bridge.runtime.BridgeMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusMetricsTest {
    private static final BridgeMetrics.Snapshot IPC =
        new BridgeMetrics.Snapshot(3, 1, 2, 0, 4_000, 8_000);

    @Test
    void derivesTpsFromAverageTickTimeAndCapsAtTwenty() {
        assertEquals(20.0, BridgeActionService.StatusMetrics.fromAverageTickMillis(40.0F, IPC).tps());
        assertEquals(10.0, BridgeActionService.StatusMetrics.fromAverageTickMillis(100.0F, IPC).tps());
        assertEquals(20.0, BridgeActionService.StatusMetrics.fromAverageTickMillis(0.0F, IPC).tps());
    }

    @Test
    void serializesControllerMetricNamesInSnakeCase() {
        var metrics = BridgeActionService.StatusMetrics.fromAverageTickMillis(50.0F, IPC);
        var json = new BridgeJsonCodec().payload(metrics).getAsJsonObject();

        assertTrue(json.has("tps"));
        assertTrue(json.has("tick_ms"));
        assertEquals(20.0, json.get("tps").getAsDouble());
        assertEquals(50.0, json.get("tick_ms").getAsDouble());
        assertEquals(3, json.get("requests").getAsLong());
    }
}
