package com.qqai.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/**
 * LLM Prompt 模板集中管理服务
 * 从 classpath:prompts.yml 加载模板，支持按 groupType 分组与 DEFAULT fallback
 * 模板变量用 {varName} 插值，变量值经 sanitize 清理花括号防注入
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private Map<String, Object> templates;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        try (InputStream is = new ClassPathResource("prompts.yml").getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            this.templates = (Map<String, Object>) root.get("templates");
            if (this.templates == null) {
                throw new IllegalStateException("prompts.yml 缺少 templates 根节点");
            }
            log.info("PromptTemplateService 已加载 prompts.yml，顶层 keys: {}", templates.keySet());
        } catch (Exception e) {
            throw new IllegalStateException("加载 prompts.yml 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 渲染 prompt 模板
     *
     * @param templateKey 模板键，如 "chat.system" / "analysis.selected" / "summary.single" / "type.recognition"
     * @param groupType   群类型，如 "GAME" / "STUDY" / null（降级 DEFAULT）
     * @param variables   变量映射，如 {messageContents: "...", groupId: "...", analysisType: "summary"}
     * @return 渲染后的 prompt 字符串
     */
    @SuppressWarnings("unchecked")
    public String render(String templateKey, String groupType, Map<String, Object> variables) {
        // 1. 按 "." 分割 templateKey，递归查找目标节点
        Object node = findNode(templateKey);
        if (node == null) {
            throw new IllegalArgumentException("未找到模板: " + templateKey);
        }
        if (!(node instanceof Map)) {
            throw new IllegalArgumentException("模板结构异常(期望 groupType->模板 Map): " + templateKey);
        }

        // 2. 先找 groupType，不存在找 DEFAULT
        Map<String, Object> typeMap = (Map<String, Object>) node;
        Object template = resolveByGroupType(typeMap, groupType);

        // 3. 如果 template 还是 Map（如 analysis.selected.DEFAULT = {summary: "..."}），用 analysisType 子键
        if (template instanceof Map) {
            Map<String, Object> subMap = (Map<String, Object>) template;
            String subKey = (variables != null && variables.get("analysisType") != null)
                    ? String.valueOf(variables.get("analysisType"))
                    : "default";
            Object leaf = subMap.get(subKey);
            if (leaf == null) {
                leaf = subMap.get("default");
            }
            if (leaf == null) {
                leaf = subMap.values().stream().findFirst().orElse(null);
            }
            template = leaf;
        }

        if (!(template instanceof String)) {
            throw new IllegalArgumentException("模板最终值不是字符串: " + templateKey
                    + (groupType != null ? " (groupType=" + groupType + ")" : ""));
        }

        // 4. 变量插值
        return interpolate((String) template, variables);
    }

    /**
     * 便捷重载：无 groupType（纯 DEFAULT）
     */
    public String render(String templateKey, Map<String, Object> variables) {
        return render(templateKey, null, variables);
    }

    @SuppressWarnings("unchecked")
    private Object findNode(String templateKey) {
        String[] parts = templateKey.split("\\.");
        Object current = templates;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private Object resolveByGroupType(Map<String, Object> typeMap, String groupType) {
        if (groupType != null && !groupType.isBlank()) {
            Object val = typeMap.get(groupType);
            if (val != null) return val;
        }
        return typeMap.get("DEFAULT");
    }

    private String interpolate(String template, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = "{" + entry.getKey() + "}";
            String value = entry.getValue() == null ? "" : sanitize(String.valueOf(entry.getValue()));
            result = result.replace(key, value);
        }
        return result;
    }

    /**
     * 清理变量值中的花括号，防止递归注入
     */
    private String sanitize(String value) {
        return value.replace("{", "〈").replace("}", "〉");
    }
}
