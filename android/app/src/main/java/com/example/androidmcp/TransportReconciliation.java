package com.example.androidmcp;

public final class TransportReconciliation {
    private TransportReconciliation() { }

    public static final class Plan {
        public final boolean startLan;
        public final boolean stopLan;
        public final boolean restartLan;
        public final boolean startTailscale;
        public final boolean stopTailscale;
        public final boolean restartTailscale;

        private Plan(boolean startLan, boolean stopLan, boolean restartLan,
                     boolean startTailscale, boolean stopTailscale, boolean restartTailscale) {
            this.startLan = startLan;
            this.stopLan = stopLan;
            this.restartLan = restartLan;
            this.startTailscale = startTailscale;
            this.stopTailscale = stopTailscale;
            this.restartTailscale = restartTailscale;
        }
    }

    public static Plan reconcile(TransportEndpoint currentLan, TransportEndpoint currentTailscale,
                                 TransportEndpoint desiredLan, TransportEndpoint desiredTailscale) {
        boolean startLan = currentLan == null && desiredLan != null;
        boolean stopLan = currentLan != null && desiredLan == null;
        boolean restartLan = currentLan != null && desiredLan != null && !currentLan.equals(desiredLan);

        boolean startTailscale = currentTailscale == null && desiredTailscale != null;
        boolean stopTailscale = currentTailscale != null && desiredTailscale == null;
        boolean restartTailscale = currentTailscale != null && desiredTailscale != null
                && !currentTailscale.equals(desiredTailscale);

        return new Plan(startLan, stopLan, restartLan,
                startTailscale, stopTailscale, restartTailscale);
    }
}
