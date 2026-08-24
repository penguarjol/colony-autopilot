package com.colonyautopilot.bridge.action;

import com.colonyautopilot.bridge.ipc.BridgeProtocol.Request;
import com.colonyautopilot.bridge.ipc.BridgeProtocol.Response;

public interface BridgeActionHandler {
    Response handle(Request request);
}
