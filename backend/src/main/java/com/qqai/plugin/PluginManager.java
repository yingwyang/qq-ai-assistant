package com.qqai.plugin;

import groovy.lang.GroovyClassLoader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * AI 回复插件管理器
 * 负责从 plugins/ 目录加载 Groovy 脚本并按顺序执行
 */
@Service
public class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    @Value("${app.plugin.dir:plugins}")
    private String pluginDir;

    private final List<AiResponsePlugin> plugins = new ArrayList<>();

    private GroovyClassLoader groovyClassLoader;

    @PostConstruct
    public void init() {
        groovyClassLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader());
        loadPlugins();
    }

    /**
     * 重新加载所有插件
     */
    public synchronized void loadPlugins() {
        plugins.clear();
        File dir = resolvePluginDir();
        if (!dir.exists() || !dir.isDirectory()) {
            logger.warn("插件目录不存在，跳过加载: {}", dir.getAbsolutePath());
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".groovy"));
        if (files == null || files.length == 0) {
            logger.info("插件目录为空: {}", dir.getAbsolutePath());
            return;
        }

        for (File file : files) {
            try {
                Class<?> clazz = groovyClassLoader.parseClass(file);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof AiResponsePlugin) {
                    AiResponsePlugin plugin = (AiResponsePlugin) instance;
                    plugins.add(plugin);
                    logger.info("加载插件: {} (order={}, file={})", plugin.name(), plugin.order(), file.getName());
                } else {
                    logger.warn("脚本未实现 AiResponsePlugin 接口: {}", file.getName());
                }
            } catch (Exception e) {
                logger.error("加载插件失败: {}", file.getName(), e);
            }
        }

        plugins.sort(Comparator.comparingInt(AiResponsePlugin::order));
        logger.info("共加载 {} 个 AI 回复格式化插件", plugins.size());
    }

    /**
     * 依次应用所有支持的插件
     *
     * @param text    原始文本
     * @param context 上下文信息，包含 source、groupId、conversationId 等
     * @return 处理后的文本
     */
    public String applyPlugins(String text, Map<String, Object> context) {
        if (text == null || plugins.isEmpty()) {
            return text;
        }

        String result = text;
        for (AiResponsePlugin plugin : plugins) {
            try {
                if (plugin.supports("text", context)) {
                    String before = result;
                    result = plugin.format(result, context);
                    logger.debug("插件 {} 处理完成，长度 {} -> {}", plugin.name(), before.length(), result.length());
                }
            } catch (Exception e) {
                logger.error("插件 {} 执行失败，跳过", plugin.name(), e);
            }
        }
        return result;
    }

    /**
     * 获取已加载的插件列表
     */
    public List<AiResponsePlugin> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }

    private File resolvePluginDir() {
        Path path = Paths.get(pluginDir);
        if (path.isAbsolute()) {
            return path.toFile();
        }
        return new File(System.getProperty("user.dir"), pluginDir);
    }
}
