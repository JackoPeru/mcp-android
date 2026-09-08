package com.example.androidmcp;

/** Pure decision policy for keeping the foreground service alive across VPN interruptions. */
public final class NetworkRecoveryPolicy {
    public enum Action { KEEP, STOP_AND_WAIT, WAIT, START, RESTART }

    private NetworkRecoveryPolicy() { }

    public static Action evaluate(boolean serverRunning, String boundAddress, String discoveredAddress) {
        String bound = boundAddress == null ? "" : boundAddress.trim();
        String discovered = discoveredAddress == null ? "" : discoveredAddress.trim();
        if (discovered.isEmpty()) {
            return serverRunning ? Action.STOP_AND_WAIT : Action.WAIT;
        }
        if (!serverRunning) return Action.START;
        if (!discovered.equals(bound)) return Action.RESTART;
        return Action.KEEP;
    }
}
