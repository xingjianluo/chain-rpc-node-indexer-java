package com.github.xingjianluo.chainrpcnodeindexerjava.sim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理各模拟节点（按 nodeId）的行为与滞后块数，线程安全。
 */
public class SimBehaviorRegistry {

    private record Setting(SimBehavior behavior, int lagBlocks) {
    }

    private final Map<String, Setting> settings = new ConcurrentHashMap<>();

    public SimBehaviorRegistry() {
    }

    public void setBehavior(String nodeId, SimBehavior behavior, int lagBlocks) {
        SimBehavior b = behavior == null ? SimBehavior.OK : behavior;
        int lag = Math.max(0, lagBlocks);
        settings.put(nodeId, new Setting(b, lag));
    }

    public void setBehavior(String nodeId, SimBehavior behavior) {
        Setting prev = settings.get(nodeId);
        setBehavior(nodeId, behavior, prev == null ? 0 : prev.lagBlocks());
    }

    public SimBehavior behaviorOf(String nodeId) {
        Setting s = settings.get(nodeId);
        return s == null ? SimBehavior.OK : s.behavior();
    }

    public int lagOf(String nodeId) {
        Setting s = settings.get(nodeId);
        return s == null ? 0 : s.lagBlocks();
    }
}
