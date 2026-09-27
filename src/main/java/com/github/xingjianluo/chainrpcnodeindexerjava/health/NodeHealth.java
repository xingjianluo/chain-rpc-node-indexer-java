package com.github.xingjianluo.chainrpcnodeindexerjava.health;

import com.github.xingjianluo.chainrpcnodeindexerjava.rpc.FailureReason;

/**
 * 单个节点的可变健康状态。所有读写都在本对象上加锁。
 *
 * <p>通过 {@link #copy()} 产生不可变快照对外暴露。
 */
public class NodeHealth {

    private final String name;
    private HealthStatus status = HealthStatus.AVAILABLE;
    private long latestHeight = -1L;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private FailureReason lastReason;
    private String lastDetail = "";
    private long lastChangedAt = System.currentTimeMillis();
    private long lastAttemptAt;

    public NodeHealth(String name) {
        this.name = name;
    }

    public synchronized String getName() { return name; }
    public synchronized HealthStatus getStatus() { return status; }
    public synchronized long getLatestHeight() { return latestHeight; }
    public synchronized int getConsecutiveFailures() { return consecutiveFailures; }
    public synchronized int getConsecutiveSuccesses() { return consecutiveSuccesses; }
    public synchronized FailureReason getLastReason() { return lastReason; }
    public synchronized String getLastDetail() { return lastDetail; }
    public synchronized long getLastChangedAt() { return lastChangedAt; }
    public synchronized long getLastAttemptAt() { return lastAttemptAt; }

    synchronized void setStatus(HealthStatus status) {
        this.status = status;
        this.lastChangedAt = System.currentTimeMillis();
    }

    synchronized void setLatestHeight(long latestHeight) {
        this.latestHeight = latestHeight;
    }

    synchronized void setConsecutiveFailures(int v) {
        this.consecutiveFailures = v;
    }

    synchronized void setConsecutiveSuccesses(int v) {
        this.consecutiveSuccesses = v;
    }

    synchronized void setLastFailure(FailureReason reason, String detail) {
        this.lastReason = reason;
        this.lastDetail = detail;
    }

    synchronized void markAttempt() {
        this.lastAttemptAt = System.currentTimeMillis();
    }

    /** 产生一份快照副本。 */
    public synchronized NodeHealth copy() {
        NodeHealth c = new NodeHealth(name);
        c.status = status;
        c.latestHeight = latestHeight;
        c.consecutiveFailures = consecutiveFailures;
        c.consecutiveSuccesses = consecutiveSuccesses;
        c.lastReason = lastReason;
        c.lastDetail = lastDetail;
        c.lastChangedAt = lastChangedAt;
        c.lastAttemptAt = lastAttemptAt;
        return c;
    }
}
