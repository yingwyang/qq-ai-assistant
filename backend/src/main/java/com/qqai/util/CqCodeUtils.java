package com.qqai.util;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CqCodeUtils {

    private static final Pattern URL_PATTERN = Pattern.compile("url=([^,\\]]+)");
    private static final Pattern FILE_PATTERN = Pattern.compile("file=([^,\\]]+)");
    private static final Pattern ID_PATTERN = Pattern.compile("id=([^,\\]]+)");

    public List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) {
            return urls;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group(1).trim());
        }
        return urls;
    }

    public String extractImageUrl(String cqCode) {
        return extractParam(cqCode, "url");
    }

    public String extractFileName(String cqCode) {
        return extractParam(cqCode, "file");
    }

    public String extractForwardId(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        try {
            int start = rawMessage.indexOf("[CQ:forward");
            if (start < 0) {
                return null;
            }
            int end = rawMessage.indexOf(']', start);
            if (end < 0) {
                return null;
            }
            String cq = rawMessage.substring(start, end + 1);
            Matcher matcher = ID_PATTERN.matcher(cq);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public Long extractReplyMessageId(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        try {
            int start = rawMessage.indexOf("[CQ:reply");
            if (start < 0) {
                return null;
            }
            int end = rawMessage.indexOf(']', start);
            if (end < 0) {
                return null;
            }
            String cq = rawMessage.substring(start, end + 1);
            Matcher matcher = ID_PATTERN.matcher(cq);
            if (matcher.find()) {
                String idStr = matcher.group(1).trim();
                return Long.parseLong(idStr);
            }
        } catch (NumberFormatException e) {
            // id 不是数字，忽略
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public boolean containsCqCode(String text, String cqType) {
        return text != null && text.contains("[CQ:" + cqType);
    }

    public String extractJsonData(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        try {
            int start = rawMessage.indexOf("[CQ:json");
            if (start < 0) {
                return null;
            }
            int end = rawMessage.indexOf(']', start);
            if (end < 0) {
                return null;
            }
            String cq = rawMessage.substring(start, end + 1);
            Pattern dataPattern = Pattern.compile("data=(.+)", Pattern.DOTALL);
            Matcher matcher = dataPattern.matcher(cq);
            if (matcher.find()) {
                String data = matcher.group(1).trim();
                if (data.startsWith("\"") && data.endsWith("\"")) {
                    data = data.substring(1, data.length() - 1);
                } else if (data.startsWith("'") && data.endsWith("'")) {
                    data = data.substring(1, data.length() - 1);
                }
                // 完整反转义所有 HTML 实体，确保存储的是有效的 JSON 字符串
                return data.replace("&amp;", "&")
                           .replace("&#91;", "[")
                           .replace("&#93;", "]")
                           .replace("&#44;", ",")
                           .replace("&#34;", "\"")
                           .replace("&#39;", "'")
                           .replace("&lt;", "<")
                           .replace("&gt;", ">")
                           .replace("&quot;", "\"");
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String extractParam(String cqCode, String paramName) {
        if (cqCode == null) {
            return null;
        }
        Pattern pattern = Pattern.compile(paramName + "=([^,\\]]+)");
        Matcher matcher = pattern.matcher(cqCode);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
