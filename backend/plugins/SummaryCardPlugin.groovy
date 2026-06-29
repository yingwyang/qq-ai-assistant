import com.qqai.plugin.AiResponsePlugin

/**
 * 摘要卡片插件
 * 将 "摘要：xxx" 或 "Summary: xxx" 开头的行转换为可折叠的 details 块
 */
class SummaryCardPlugin implements AiResponsePlugin {

    String name() { 'SummaryCardPlugin' }

    int order() { 200 }

    boolean supports(String contentType, Map context) {
        'text' == contentType
    }

    String format(String text, Map context) {
        text.replaceAll(/(?m)^(?:摘要|Summary)[：:]\s*(.+)$/) { match, title ->
            "<details>\n<summary>摘要：${title}</summary>\n\n${match}\n</details>"
        }
    }
}
