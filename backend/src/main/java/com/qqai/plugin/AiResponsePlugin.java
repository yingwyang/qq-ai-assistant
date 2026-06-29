package com.qqai.plugin;

import java.util.Map;

/**
 * AI 回复格式化插件接口
 * 所有放置在 plugins/ 目录下的 Groovy 脚本都需要实现此接口
 */
public interface AiResponsePlugin {

    /**
     * 插件名称
     */
    String name();

    /**
     * 执行顺序，数值越小越先执行
     */
    int order();

    /**
     * 判断当前插件是否支持处理该内容
     *
     * @param contentType 内容类型，例如 "text"
     * @param context     上下文信息
     */
    boolean supports(String contentType, Map<String, Object> context);

    /**
     * 格式化文本
     *
     * @param text    原始文本
     * @param context 上下文信息
     * @return 格式化后的文本
     */
    String format(String text, Map<String, Object> context);
}
