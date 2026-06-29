import com.qqai.plugin.AiResponsePlugin

/**
 * 自动链接插件
 * 将文本中的裸 URL 转换为 Markdown 链接
 */
class AutoLinkPlugin implements AiResponsePlugin {

    String name() { 'AutoLinkPlugin' }

    int order() { 100 }

    boolean supports(String contentType, Map context) {
        'text' == contentType
    }

    String format(String text, Map context) {
        // 避免替换已经是 Markdown 链接内的 URL
        text.replaceAll(/(?<!\]\()https?:\/\/[^\s<>()"]+/) { url -> "[${url}](${url})" }
    }
}
