package com.qqai.adapter.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AstrBot 多模态消息支持：本地图片 -> /api/v1/file 换 attachment_id -> 多模态 message 段。
 * 主聊天与分析链路共用（控制器里的同名私有方法委托到这里）。
 */
@Component
public class AstrBotMediaSupport {

    private static final Logger log = LoggerFactory.getLogger(AstrBotMediaSupport.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${astrbot.api-url:http://localhost:6185}")
    private String astrBotApiUrl;

    public AstrBotMediaSupport(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public Object buildMultimodalMessage(String prompt, List<String> imageUrls, String token) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return prompt;
        }
        try {
            List<String> attachmentIds = uploadImagesToAstrBot(imageUrls, token);
            if (attachmentIds.isEmpty()) {
                log.info("图片全部上传失败，回退到纯文本方案");
                return prompt;
            }
            List<Map<String, Object>> messageParts = new ArrayList<>();
            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "plain");
            textPart.put("text", prompt);
            messageParts.add(textPart);
            for (String attachmentId : attachmentIds) {
                Map<String, Object> imagePart = new HashMap<>();
                imagePart.put("type", "image");
                imagePart.put("attachment_id", attachmentId);
                messageParts.add(imagePart);
            }
            log.info("构造多模态message: 1段文本 + {}张图片", attachmentIds.size());
            return messageParts;
        } catch (Exception e) {
            log.warn("构造多模态message失败，回退到纯文本: {}", e.getMessage());
            return prompt;
        }
    }

    public List<String> uploadImagesToAstrBot(List<String> imageUrls, String token) {
        if (imageUrls == null || imageUrls.isEmpty()) return Collections.emptyList();
        List<String> attachmentIds = new ArrayList<>();
        int maxImages = 5;
        long maxSize = 10L * 1024 * 1024;
        int uploaded = 0;
        for (String imageUrl : imageUrls) {
            if (uploaded >= maxImages) break;
            try {
                File imageFile = resolveImageFileFromUrl(imageUrl);
                if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) {
                    log.debug("图片文件不存在，跳过: {}", imageUrl);
                    continue;
                }
                if (imageFile.length() > maxSize) {
                    log.debug("图片过大({}B)，跳过: {}", imageFile.length(), imageUrl);
                    continue;
                }
                String uploadUrl = astrBotApiUrl + "/api/v1/file";
                HttpHeaders uploadHeaders = new HttpHeaders();
                uploadHeaders.set("X-API-Key", token);
                uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
                MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
                multipartBody.add("file", new FileSystemResource(imageFile));
                HttpEntity<MultiValueMap<String, Object>> uploadEntity = new HttpEntity<>(multipartBody, uploadHeaders);
                ResponseEntity<String> uploadResp = restTemplate.postForEntity(uploadUrl, uploadEntity, String.class);
                if (uploadResp.getStatusCode().is2xxSuccessful() && uploadResp.getBody() != null) {
                    JsonNode respJson = objectMapper.readTree(uploadResp.getBody());
                    String attachmentId = respJson.path("data").path("attachment_id").asText(null);
                    if (attachmentId != null && !attachmentId.isEmpty()) {
                        attachmentIds.add(attachmentId);
                        uploaded++;
                    }
                }
            } catch (Exception e) {
                log.warn("上传图片到AstrBot失败 {}: {}", imageUrl, e.getMessage());
            }
        }
        return attachmentIds;
    }

    public File resolveImageFileFromUrl(String imageUrl) {
        try {
            String path;
            if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                int idx = imageUrl.indexOf("://");
                int pathStart = imageUrl.indexOf("/", idx + 3);
                path = pathStart >= 0 ? imageUrl.substring(pathStart) : "";
            } else {
                path = imageUrl;
            }
            if (path.startsWith("/images/")) {
                return new File("uploads" + path);
            } else if (path.startsWith("/uploads/")) {
                return new File("uploads" + path.substring("/uploads".length()));
            }
        } catch (Exception e) {
            log.debug("解析图片URL失败 {}: {}", imageUrl, e.getMessage());
        }
        return null;
    }

    public String augmentPromptForImages(String prompt, List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return prompt;
        return prompt + "\n\n【重要】本次请求已随附 " + imageUrls.size() + " 张真实图片"
                + "（多模态消息段，可以直接看到画面）。凡是占位符 [图片] 对应的消息，"
                + "请直接描述图片的实际内容（人物/物体/场景/截图文字/表情包含义等），"
                + "不要再输出「内容未知」「未提供可辨内容」「无法查看图片」这类说法；"
                + "音视频仍按占位符处理，不要编造。"
                + "输出格式仍严格遵循上面的要求。";
    }
}
