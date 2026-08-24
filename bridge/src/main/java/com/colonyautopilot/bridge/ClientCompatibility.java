package com.colonyautopilot.bridge;

public final class ClientCompatibility {
    private ClientCompatibility() {
    }

    public static boolean acceptsRemoteVersion(String remoteVersion, boolean isFromServer) {
        return true;
    }
}
