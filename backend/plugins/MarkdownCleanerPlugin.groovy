import com.qqai.plugin.AiResponsePlugin

/**
 * Markdown 清理插件
 * 兜底清理工具 JSON 残留与多余空行
 */
class MarkdownCleanerPlugin implements AiResponsePlugin {

    String name() { 'MarkdownCleanerPlugin' }

    int order() { 1000 }

    boolean supports(String contentType, Map context) {
        'text' == contentType
    }

    String format(String text, Map context) {
        String cleaned = text
        // 清理可能残留的工具调用 JSON（含 id 字段的对象块）
        cleaned = cleaned.replaceAll(/(?s)\s*\{\s*"id"\s*:\s*"[a-f0-9-]+"[^}]*(?:\}\s*)+/, ' ')
        // 合并三个及以上连续换行为两个
        cleaned = cleaned.replaceAll(/\n{3,}/, '\n\n')
        return cleaned.trim()
    }
}
