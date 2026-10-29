package com.qqai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 多机器人配置（每用户一个自己的 QQ 机器人）。 */
@ConfigurationProperties(prefix = "bot")
public class BotProperties {

    private boolean enabled = true;
    private boolean seedDefault = true;
    private Long defaultOwner;
    private String defaultQq = "2488130337";
    private String defaultName = "默认机器人";
    private int defaultNapcatPort = 6099;
    private int defaultOnebotPort = 6100;
    private String defaultWorkdir = "../../napcat/NapCat.Shell";
    private int portRangeStart = 16100;
    private int maxPerUser = 1;
    private String workdirRoot = "bots";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSeedDefault() { return seedDefault; }
    public void setSeedDefault(boolean seedDefault) { this.seedDefault = seedDefault; }
    public Long getDefaultOwner() { return defaultOwner; }
    public void setDefaultOwner(Long defaultOwner) { this.defaultOwner = defaultOwner; }
    public String getDefaultQq() { return defaultQq; }
    public void setDefaultQq(String defaultQq) { this.defaultQq = defaultQq; }
    public String getDefaultName() { return defaultName; }
    public void setDefaultName(String defaultName) { this.defaultName = defaultName; }
    public int getDefaultNapcatPort() { return defaultNapcatPort; }
    public void setDefaultNapcatPort(int defaultNapcatPort) { this.defaultNapcatPort = defaultNapcatPort; }
    public int getDefaultOnebotPort() { return defaultOnebotPort; }
    public void setDefaultOnebotPort(int defaultOnebotPort) { this.defaultOnebotPort = defaultOnebotPort; }
    public String getDefaultWorkdir() { return defaultWorkdir; }
    public void setDefaultWorkdir(String defaultWorkdir) { this.defaultWorkdir = defaultWorkdir; }
    public int getPortRangeStart() { return portRangeStart; }
    public void setPortRangeStart(int portRangeStart) { this.portRangeStart = portRangeStart; }
    public int getMaxPerUser() { return maxPerUser; }
    public void setMaxPerUser(int maxPerUser) { this.maxPerUser = maxPerUser; }
    public String getWorkdirRoot() { return workdirRoot; }
    public void setWorkdirRoot(String workdirRoot) { this.workdirRoot = workdirRoot; }
}
