package com.example.androidmcp;

import org.junit.Test;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class IdleExecutorPolicyTest {
    @Test public void timeoutSchedulerAllowsItsOnlyWorkerToDisappearWhenIdle() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        try {
            McpHttpServer.configureTimeoutScheduler(scheduler);
            assertTrue(scheduler.allowsCoreThreadTimeOut());
            assertEquals(30L, scheduler.getKeepAliveTime(TimeUnit.SECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test public void updaterKeepsNoPermanentWorkerAfterChecksFinish() {
        ThreadPoolExecutor executor = LowPowerSessionPolicy.createSingleIdleWorkerExecutor("test-updater");
        try {
            assertEquals(0, executor.getCorePoolSize());
            assertEquals(1, executor.getMaximumPoolSize());
            assertEquals(30L, executor.getKeepAliveTime(TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
