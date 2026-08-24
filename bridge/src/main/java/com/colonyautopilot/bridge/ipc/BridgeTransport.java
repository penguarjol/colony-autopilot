package com.colonyautopilot.bridge.ipc;

import java.io.IOException;
import java.util.List;

public interface BridgeTransport extends AutoCloseable {
    List<InboundMessage> poll(int maximum) throws IOException;

    void complete(InboundMessage message, String responseJson) throws IOException;

    void reject(InboundMessage message, String reason) throws IOException;

    @Override
    default void close() throws IOException {
    }

    record InboundMessage(String token, String body) {
    }
}
