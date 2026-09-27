package com.github.xingjianluo.chainrpcnodeindexerjava.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.* 配置绑定。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String dataDir = "./data";
    private final Health health = new Health();
    private final Indexer indexer = new Indexer();
    private final Simulated simulated = new Simulated();
    private final List<Upstream> upstreams = new ArrayList<>();

    public static class Health {
        private int failThreshold = 3;
        private int recoverSuccesses = 2;
        private long probeCooldownMillis = 1000;
        private long lagThreshold = 2;

        public int getFailThreshold() { return failThreshold; }
        public void setFailThreshold(int v) { this.failThreshold = v; }
        public int getRecoverSuccesses() { return recoverSuccesses; }
        public void setRecoverSuccesses(int v) { this.recoverSuccesses = v; }
        public long getProbeCooldownMillis() { return probeCooldownMillis; }
        public void setProbeCooldownMillis(long v) { this.probeCooldownMillis = v; }
        public long getLagThreshold() { return lagThreshold; }
        public void setLagThreshold(long v) { this.lagThreshold = v; }
    }

    public static class Indexer {
        private boolean enabled = true;
        private long tickMillis = 500;
        private int maxBlocksPerTick = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getTickMillis() { return tickMillis; }
        public void setTickMillis(long v) { this.tickMillis = v; }
        public int getMaxBlocksPerTick() { return maxBlocksPerTick; }
        public void setMaxBlocksPerTick(int v) { this.maxBlocksPerTick = v; }
    }

    public static class Simulated {
        private int chainHeight = 12;
        private long mineIntervalMillis = 3000;

        public int getChainHeight() { return chainHeight; }
        public void setChainHeight(int v) { this.chainHeight = v; }
        public long getMineIntervalMillis() { return mineIntervalMillis; }
        public void setMineIntervalMillis(long v) { this.mineIntervalMillis = v; }
    }

    public static class Upstream {
        private String name;
        private String type = "simulated";
        private String url;
        private long timeoutMillis = 1000;
        private long lagBy = 0;

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public long getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(long v) { this.timeoutMillis = v; }
        public long getLagBy() { return lagBy; }
        public void setLagBy(long v) { this.lagBy = v; }
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String v) { this.dataDir = v; }
    public Health getHealth() { return health; }
    public Indexer getIndexer() { return indexer; }
    public Simulated getSimulated() { return simulated; }
    public List<Upstream> getUpstreams() { return upstreams; }
}
