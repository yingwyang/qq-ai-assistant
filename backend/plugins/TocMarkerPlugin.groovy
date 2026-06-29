import com.qqai.plugin.AiResponsePlugin

/**
 * 目录标记插件
 * 当文本包含 Markdown 标题时，在开头插入 [TOC] 标记供前端生成目录
 */
class TocMarkerPlugin implements AiResponsePlugin {

    String name() { 'TocMarkerPlugin' }

    int order() { 50 }

    boolean supports(String contentType, Map context) {
        'text' == contentType
    }

    String format(String text, Map context) {
        if (text.contains('[TOC]')) {
            return text
        }
        if (text =~ /(?m)^#{1,6}\s+/) {
            return '[TOC]\n\n' + text
        }
        return text
    }
}
