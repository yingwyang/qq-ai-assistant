package com.qqai.component;

/** 组件进程运行器：supervised 与 detached-launcher 两种实现。 */
public interface ComponentRuntime {

    ComponentModel.Started start(ComponentModel.ComponentDescriptor descriptor) throws Exception;

    void stop(ComponentModel.ComponentDescriptor descriptor, ComponentModel.ComponentRecord record,
              boolean allowPortFallback) throws Exception;

    ComponentModel.ProbeResult probe(ComponentModel.ComponentDescriptor descriptor);
}
