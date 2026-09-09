package com.example.androidmcp;

import org.junit.Test;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
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

    @Test public void rpcExecutorStartsFourRequestsInParallelAndCanReturnToZeroIdleWorkers()
            throws Exception {
        ThreadPoolExecutor executor = McpHttpServer.createRpcExecutor();
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(4, executor.getCorePoolSize());
            assertEquals(4, executor.getMaximumPoolSize());
            assertTrue(executor.allowsCoreThreadTimeOut());
            assertEquals(16, executor.getQueue().remainingCapacity());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
