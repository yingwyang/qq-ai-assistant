package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class GptSovitsService {

    private static final Logger log = LoggerFactory.getLogger(GptSovitsService.class);

    @Value("${gpt-sovits.api-url}")
    private String gptSovitsApiUrl;

    @Value("${gpt-sovits.token}")
    private String gptSovitsToken;

    @Value("${gpt-sovits.ref-audio-path}")
    private String refAudioPath;

    @Value("${gpt-sovits.prompt-text}")
    private String promptText;

    @Value("${gpt-sovits.prompt-lang}")
    private String promptLang;

    @Value("${gpt-sovits.text-lang}")
    private String textLang;

    @Value("${gpt-sovits.output-dir}")
    private String outputDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用 GPT-SoVITS 生成语音并保存到本地，返回前端可访问的相对 URL
     */
    public String generateVoice(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("合成文本不能为空");
        }

        // 限制文本长度，避免生成长文本耗时长
        String trimmedText = text.length() > 500 ? text.substring(0, 500) + "……" : text;

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(gptSovitsApiUrl + "/tts");
        httpPost.setHeader("Content-Type", "application/json");

        // 构建请求体 - 参考 GPT-SoVITS api_v2.py 的接口规范
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("text", trimmedText);
        requestBody.put("text_lang", textLang);
        requestBody.put("ref_audio_path", refAudioPath);
        requestBody.put("prompt_text", promptText != null ? promptText : "");
        requestBody.put("prompt_lang", promptLang);
        requestBody.put("media_type", "wav");
        requestBody.put("streaming_mode", false);
        requestBody.put("speed_factor", 1.0);

        httpPost.setEntity(new StringEntity(requestBody.toString()));

        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            int statusCode = response.getCode();

            // 检查内容类型，如果返回的是 JSON 错误信息，读取并抛出
            if (statusCode != 200) {
                StringBuilder sb = new StringBuilder();
                try (InputStream is = response.getEntity().getContent()) {
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        sb.append(new String(buf, 0, len, "UTF-8"));
                    }
                }
                throw new IOException("GPT-SoVITS 返回错误 (HTTP " + statusCode + "): " + sb.toString());
            }

            // 确保输出目录存在
            File outDir = new File(outputDir);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            File dateDir = new File(outDir, dateStr);
            if (!dateDir.exists()) {
                dateDir.mkdirs();
            }

            String fileName = UUID.randomUUID().toString() + ".wav";
            File outFile = new File(dateDir, fileName);

            // 直接将响应流保存为 wav 文件
            try (InputStream is = response.getEntity().getContent()) {
                Files.copy(is, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // 返回前端可访问的相对路径（Spring WebMvcConfig 配置了 /uploads/** 静态资源映射）
            return "/uploads/tts/" + dateStr + "/" + fileName;
        } finally {
            httpClient.close();
        }
    }

    private Process gptSovitsProcess;

    public void startGptSovits() throws Exception {
        try {
            String projectRoot = System.getProperty("user.dir");
            String gptSovitsPath = projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "GPT-SoVITS-v2pro-20250604-nvidia50";
            File gptSovitsDir = new File(gptSovitsPath).getCanonicalFile();
            String pythonPath = gptSovitsDir.getAbsolutePath() + File.separator + "runtime" + File.separator + "python.exe";
            String apiScriptPath = gptSovitsDir.getAbsolutePath() + File.separator + "api_v2.py";

            File pythonFile = new File(pythonPath);
            if (!pythonFile.exists()) {
                throw new IOException("GPT-SoVITS python.exe not found at: " + pythonPath);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "cmd.exe", "/c", "start", "cmd.exe", "/k",
                    pythonPath, "-I", apiScriptPath, "-a", "127.0.0.1", "-p", "8000"
            );
            processBuilder.directory(gptSovitsDir);
            processBuilder.inheritIO();

            gptSovitsProcess = processBuilder.start();

            Thread.sleep(5000);
            log.info("GPT-SoVITS started successfully from: {}", gptSovitsDir.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to start GPT-SoVITS: {}", e.getMessage());
            throw e;
        }
    }

    public void stopGptSovits() throws Exception {
        try {
            if (gptSovitsProcess != null && gptSovitsProcess.isAlive()) {
                gptSovitsProcess.destroy();
                log.info("GPT-SoVITS stopped successfully");
            } else {
                Runtime.getRuntime().exec("taskkill /F /IM python.exe");
                log.info("GPT-SoVITS processes terminated");
            }
        } catch (Exception e) {
            log.error("Failed to stop GPT-SoVITS: {}", e.getMessage());
            throw e;
        }
    }
}
