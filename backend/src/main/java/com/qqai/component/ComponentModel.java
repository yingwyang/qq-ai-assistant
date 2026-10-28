package com.qqai.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组件插件的共享模型：描述符、状态、视图、探测结果。
 *
 * <p>只有组件运行层使用；能力契约（capability 包）不依赖这些类型。</p>
 */
public final class ComponentModel {

    private ComponentModel() {
    }

    public enum LaunchMode {
        /** 由本项目直接拉起子进程，可拿到 PID/退出码/日志 */
        SUPERVISED,
        /** 由启动器自行提权/拉起（NapCat launcher），只能探测，无法可靠持有 PID */
        DETACHED_LAUNCHER
    }

    public enum StopStrategy {
        /** 只结束自己拉起的进程树 */
        PROCESS_TREE,
        /** 按进程名 + 端口结束（保留 NapCat 既有语义） */
        PROCESS_NAMES
    }

    public enum ComponentState {
        STOPPED, STARTING, RUNNING, DEGRADED, STOPPING, FAILED, BLOCKED
    }

    public static class ProbeSpec {
        private String type = "tcp";
        private int port;
        private String path;
        private int timeoutMs = 1000;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class ComponentDescriptor {
        private String id;
        private String legacyKey;
        private String displayName;
        private String capability;
        private LaunchMode launchMode = LaunchMode.SUPERVISED;
        private List<String> command = new ArrayList<>();
        private String workdir;
        private Map<String, String> env = new LinkedHashMap<>();
        private List<Integer> ports = new ArrayList<>();
        private ProbeSpec probe = new ProbeSpec();
        private long startupTimeoutMs = 60000;
        private StopStrategy stopStrategy = StopStrategy.PROCESS_TREE;
        private List<String> processNames = new ArrayList<>();
        private int order;
        private boolean enabled = true;
        private String webUi;
        /** 外部启动（owned=false）时是否允许停止；保留既有 UI 行为时置 true */
        private boolean adoptExternal = false;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLegacyKey() { return legacyKey; }
        public void setLegacyKey(String legacyKey) { this.legacyKey = legacyKey; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getCapability() { return capability; }
        public void setCapability(String capability) { this.capability = capability; }
        public LaunchMode getLaunchMode() { return launchMode; }
        public void setLaunchMode(LaunchMode launchMode) { this.launchMode = launchMode; }
        public List<String> getCommand() { return command; }
        public void setCommand(List<String> command) { this.command = command; }
        public String getWorkdir() { return workdir; }
        public void setWorkdir(String workdir) { this.workdir = workdir; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public List<Integer> getPorts() { return ports; }
        public void setPorts(List<Integer> ports) { this.ports = ports; }
        public ProbeSpec getProbe() { return probe; }
        public void setProbe(ProbeSpec probe) { this.probe = probe; }
        public long getStartupTimeoutMs() { return startupTimeoutMs; }
        public void setStartupTimeoutMs(long startupTimeoutMs) { this.startupTimeoutMs = startupTimeoutMs; }
        public StopStrategy getStopStrategy() { return stopStrategy; }
        public void setStopStrategy(StopStrategy stopStrategy) { this.stopStrategy = stopStrategy; }
        public List<String> getProcessNames() { return processNames; }
        public void setProcessNames(List<String> processNames) { this.processNames = processNames; }
        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getWebUi() { return webUi; }
        public void setWebUi(String webUi) { this.webUi = webUi; }
        public boolean isAdoptExternal() { return adoptExternal; }
        public void setAdoptExternal(boolean adoptExternal) { this.adoptExternal = adoptExternal; }
    }

    /** 持久化到 data/components-state.json 的单个组件状态 */
    public static class ComponentRecord {
        public String state = "STOPPED";
        public Long pid;
        public String pidStartInstant;
        public boolean owned = true;
        public String startedAt;
        public String updatedAt;
        public String lastHealthAt;
        public String lastError;

        public ComponentRecord copy() {
            ComponentRecord c = new ComponentRecord();
            c.state = state;
            c.pid = pid;
            c.pidStartInstant = pidStartInstant;
            c.owned = owned;
            c.startedAt = startedAt;
            c.updatedAt = updatedAt;
            c.lastHealthAt = lastHealthAt;
            c.lastError = lastError;
            return c;
        }
    }

    /** 返回给前端的视图 */
    public static class ComponentView {
        public String id;
        public String legacyKey;
        public String displayName;
        public String capability;
        public String state;
        public boolean running;
        public boolean healthy;
        public boolean owned;
        public Long pid;
        public List<Integer> ports;
        public String webUi;
        public String lastError;
        public String startedAt;
        public Map<String, Boolean> actions = new LinkedHashMap<>();
    }

    public record Started(Long pid, String pidStartInstant) {
    }

    public record ProbeResult(boolean reachable, long latencyMs, String detail) {
    }
}
