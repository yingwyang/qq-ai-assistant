package com.qqai.component;

import com.qqai.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 校验并保存组件描述符，按 order 排序。 */
@Component
public class ComponentRegistry {

    private static final Logger log = LoggerFactory.getLogger(ComponentRegistry.class);

    private final Map<String, ComponentModel.ComponentDescriptor> byId = new LinkedHashMap<>();
    private final List<ComponentModel.ComponentDescriptor> ordered;

    public ComponentRegistry(ComponentProperties properties) {
        if (properties.getComponents() == null) {
            log.error("组件配置为 null（配置绑定失败），按空列表处理");
            this.ordered = List.of();
            return;
        }
        for (ComponentModel.ComponentDescriptor d : properties.getComponents()) {
            if (d.getId() == null || d.getId().isBlank()) {
                log.error("组件描述符缺少 id，已跳过");
                continue;
            }
            if (byId.putIfAbsent(d.getId(), d) != null) {
                throw new IllegalStateException("组件 id 重复: " + d.getId());
            }
            if (d.getCommand() == null || d.getCommand().isEmpty()) {
                log.error("组件 {} 未配置启动命令，启动时会返回 400", d.getId());
            }
        }
        List<ComponentModel.ComponentDescriptor> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparingInt(ComponentModel.ComponentDescriptor::getOrder));
        this.ordered = List.copyOf(list);
        log.info("组件注册完成: {}", ordered.stream().map(ComponentModel.ComponentDescriptor::getId).toList());
    }

    public List<ComponentModel.ComponentDescriptor> ordered() {
        return ordered.stream().filter(ComponentModel.ComponentDescriptor::isEnabled).toList();
    }

    public List<ComponentModel.ComponentDescriptor> all() {
        return ordered;
    }

    public ComponentModel.ComponentDescriptor get(String id) {
        ComponentModel.ComponentDescriptor d = byId.get(id);
        if (d == null) {
            throw new BizException(404, "未知组件: " + id);
        }
        return d;
    }
}
