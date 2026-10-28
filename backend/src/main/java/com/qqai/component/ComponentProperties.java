package com.qqai.component;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 组件描述符配置。默认值在 application.yml 的 component.components 中给出，
 * 与改造前的启动命令/端口逐项对齐。
 */
@ConfigurationProperties(prefix = "component")
public class ComponentProperties {

    private long healthIntervalMs = 5000;
    private String stateFile = "data/components-state.json";
    private List<ComponentModel.ComponentDescriptor> components = new ArrayList<>();

    public long getHealthIntervalMs() { return healthIntervalMs; }
    public void setHealthIntervalMs(long healthIntervalMs) { this.healthIntervalMs = healthIntervalMs; }
    public String getStateFile() { return stateFile; }
    public void setStateFile(String stateFile) { this.stateFile = stateFile; }
    public List<ComponentModel.ComponentDescriptor> getComponents() { return components; }
    public void setComponents(List<ComponentModel.ComponentDescriptor> components) { this.components = components; }
}
