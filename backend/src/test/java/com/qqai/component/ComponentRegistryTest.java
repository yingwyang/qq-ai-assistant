package com.qqai.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComponentRegistryTest {

    private static ComponentModel.ComponentDescriptor desc(String id, int order) {
        ComponentModel.ComponentDescriptor d = new ComponentModel.ComponentDescriptor();
        d.setId(id);
        d.setOrder(order);
        d.setCommand(List.of("cmd.exe", "/c", "echo", id));
        d.setWorkdir(".");
        return d;
    }

    @Test
    @DisplayName("按 order 排序，禁用项不进入 ordered()")
    void ordersAndFilters() {
        ComponentProperties props = new ComponentProperties();
        ComponentModel.ComponentDescriptor disabled = desc("c", 5);
        disabled.setEnabled(false);
        props.setComponents(List.of(desc("b", 20), desc("a", 10), disabled));

        ComponentRegistry registry = new ComponentRegistry(props);

        assertEquals(List.of("a", "b"), registry.ordered().stream()
                .map(ComponentModel.ComponentDescriptor::getId).toList());
        assertEquals(3, registry.all().size());
    }

    @Test
    @DisplayName("重复 id 启动即失败（配置错误要早暴露）")
    void duplicateIdFailsFast() {
        ComponentProperties props = new ComponentProperties();
        props.setComponents(List.of(desc("dup", 1), desc("dup", 2)));

        assertThrows(IllegalStateException.class, () -> new ComponentRegistry(props));
    }

    @Test
    @DisplayName("未知组件返回 404 BizException")
    void unknownId() {
        ComponentProperties props = new ComponentProperties();
        props.setComponents(List.of(desc("a", 1)));
        ComponentRegistry registry = new ComponentRegistry(props);

        var ex = assertThrows(com.qqai.exception.BizException.class, () -> registry.get("nope"));
        assertEquals(404, ex.getCode());
    }
}
