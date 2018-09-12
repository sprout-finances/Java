package com.leo.sdk.aws.payload;

import com.leo.sdk.ExecutorManager;
import com.leo.sdk.config.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.math.RoundingMode.HALF_EVEN;
import static java.math.RoundingMode.HALF_UP;
import static java.util.concurrent.TimeUnit.SECONDS;

@Singleton
public class InternalThresholdMonitor implements ThresholdMonitor {
    private static final Logger log = LoggerFactory.getLogger(InternalThresholdMonitor.class);

    private final long maxBytesPerSecond;
    private final BigDecimal warningThreshold;
    private final ExecutorManager executorManager;
    private final AtomicBoolean running;
    private final Lock lock = new ReentrantLock();
    private final Condition thresholdCheck = lock.newCondition();
    private final AtomicLong currentLevel = new AtomicLong();
    private final AtomicBoolean failover = new AtomicBoolean(false);

    @Inject
    public InternalThresholdMonitor(ConnectorConfig config, ExecutorManager executorManager) {
        maxBytesPerSecond = config.longValueOrElse("Stream.BytesPerSecondFailover", 50000L);
        warningThreshold = new BigDecimal(maxBytesPerSecond)
                .multiply(new BigDecimal(".8"))
                .setScale(0, HALF_UP);
        this.executorManager = executorManager;
        this.running = new AtomicBoolean(true);
        if (maxBytesPerSecond > 0) {
            CompletableFuture.runAsync(this::checkThresholds, executorManager.get());
        }
    }

    @Override
    public void addBytes(long bytes) {
        currentLevel.addAndGet(bytes);
    }

    @Override
    public boolean isFailover() {
        return failover.get();
    }

    @Override
    public void end() {
        running.set(false);
        lock.lock();
        try {
            thresholdCheck.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean wasOverThreshold() {
        return currentLevel.getAndSet(0) > maxBytesPerSecond;
    }

    private boolean isOverThreshold() {
        return currentLevel.get() > maxBytesPerSecond;
    }

    private void checkThresholds() {
        do {
            if (wasOverThreshold()) {
                boolean wasFailover = failover.getAndSet(true);
                if (!wasFailover) {
                    log.warn("Bytes per second exceeded {}: failover enabled", maxBytesPerSecond);
                }
            } else {
                CompletableFuture.runAsync(this::delayedClearCheck, executorManager.get());
                BigDecimal level = new BigDecimal(currentLevel.get());
                if (level.compareTo(warningThreshold) > 0) {
                    BigDecimal percentageOfThreshold = percentageOfThreshold(level);
                    log.warn("Bytes per second are currently {}% of your failover threshold", percentageOfThreshold);
                }
            }

            lock.lock();
            try {
                thresholdCheck.await(1, SECONDS);
            } catch (InterruptedException i) {
                running.set(false);
                log.info("Threshold monitor stopped");
            } finally {
                lock.unlock();
            }
        } while (running.get());
    }

    private BigDecimal percentageOfThreshold(BigDecimal level) {
        return level
                .divide(new BigDecimal(maxBytesPerSecond), HALF_EVEN)
                .movePointRight(2)
                .setScale(0, HALF_EVEN);
    }

    private void delayedClearCheck() {
        lock.lock();
        try {
            thresholdCheck.await(10, SECONDS);
            if (running.get() && failover.get() && isOverThreshold()) {
                log.warn("Failover remains in place");
            } else if (failover.get()) {
                failover.set(false);
                log.info("Cleared failover");
            }
        } catch (InterruptedException i) {
            running.set(false);
        } finally {
            lock.unlock();
        }
    }
}
