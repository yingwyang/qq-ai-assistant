package com.qqai.service;

import com.qqai.common.ProcessManager;
import com.qqai.config.GptSovitsConfig;
import com.qqai.config.GptSovitsConfig.CharacterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private GptSovitsConfig gptSovitsConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当前已加载的角色名（GPT-SoVITS 全局状态，同一时刻只有一个角色生效） */
    private volatile String currentCharacter = null;

    /** 用户级角色选择（userId -> 角色名），切换时按用户记录 */
    private final Map<Long, String> userCharacterMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 不在启动时立即切换模型（GPT-SoVITS 可能还没启动）
        // 仅记录默认角色，首次 TTS 请求时按需切换
        String def = gptSovitsConfig.getDefaultCharacter();
        if (def != null && gptSovitsConfig.findCharacter(def) != null) {
            currentCharacter = def;
            log.info("GPT-SoVITS 默认角色: {}", def);
        }
    }

    /**
     * 获取可用角色列表
     */
    public List<Map<String, String>> listCharacters() {
        List<Map<String, String>> result = new ArrayList<>();
        for (CharacterConfig c : gptSovitsConfig.getCharacters()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", c.getName());
            item.put("label", c.getLabel());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取当前默认/已加载的角色名
     */
    public String getCurrentCharacter() {
        return currentCharacter;
    }

    /**
     * 获取用户选择的角色（fallback 到全局默认）
     */
    public String getUserCharacter(Long userId) {
        if (userId != null) {
            String userChar = userCharacterMap.get(userId);
            if (userChar != null) return userChar;
        }
        return currentCharacter;
    }

    /**
     * 切换 GPT-SoVITS 全局模型权重到指定角色
     * 注意：GPT-SoVITS API 的 /set_gpt_weights 和 /set_sovits_weights 是全局状态的，
     * 切换会影响所有并发请求。当前实现按用户记录选择，实际合成时用全局状态。
     */
    public synchronized String switchCharacter(String characterName) throws Exception {
        CharacterConfig cc = gptSovitsConfig.findCharacter(characterName);
        if (cc == null) {
            throw new IllegalArgumentException("未知角色: " + characterName);
        }

        // 已加载相同角色则跳过
        if (characterName.equals(currentCharacter)) {
            log.info("角色 {} 已是当前角色，跳过切换", characterName);
            return characterName;
        }

        String modelRoot = gptSovitsConfig.getModelRoot();
        String gptFullPath = modelRoot + "/" + cc.getGptPath();
        String sovitsFullPath = modelRoot + "/" + cc.getSovitsPath();

        // 调用 GPT-SoVITS API 切换 GPT 权重
        callSetWeights(gptSovitsApiUrl + "/set_gpt_weights", gptFullPath);
        // 调用 GPT-SoVITS API 切换 SoVITS 权重
        callSetWeights(gptSovitsApiUrl + "/set_sovits_weights", sovitsFullPath);

        currentCharacter = characterName;
        log.info("GPT-SoVITS 角色已切换: {} (GPT={}, SoVITS={})", characterName, cc.getGptPath(), cc.getSovitsPath());
        return characterName;
    }

    /**
     * 为指定用户设置角色偏好（切换全局模型 + 记录用户选择）
     */
    public String setCharacterForUser(Long userId, String characterName) throws Exception {
        switchCharacter(characterName);
        if (userId != null) {
            userCharacterMap.put(userId, characterName);
        }
        return characterName;
    }

    private void callSetWeights(String url, String weightsPath) throws Exception {
        String fullUrl = url + "?weights_path=" + URLEncoder.encode(weightsPath, StandardCharsets.UTF_8);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(fullUrl);
            try (CloseableHttpResponse resp = client.execute(httpGet)) {
                int code = resp.getCode();
                if (code != 200) {
                    String body = new String(resp.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                    throw new IOException("切换权重失败 HTTP " + code + ": " + body);
                }
            }
        }
    }

    /**
     * 调用 GPT-SoVITS 生成语音并保存到本地，返回前端可访问的相对 URL
     * @param text 合成文本
     * @param userId 用户ID
     * @param characterName 指定角色名（null 则用用户偏好/默认）
     */
    public String generateVoice(String text, Long userId, String characterName) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("合成文本不能为空");
        }

        // 角色选择：优先参数 > 用户偏好 > 全局默认
        String targetChar = characterName;
        if (targetChar == null || targetChar.isBlank()) {
            targetChar = getUserCharacter(userId);
        }
        // 如果指定了角色且与当前全局角色不同，先切换
        if (targetChar != null && !targetChar.equals(currentCharacter)) {
            switchCharacter(targetChar);
        }

        // 获取角色配置（用于参考音频和 prompt）
        CharacterConfig cc = gptSovitsConfig.findCharacter(targetChar);
        String useRefAudio = refAudioPath;
        String usePromptText = "";
        String usePromptLang = promptLang;
        if (cc != null) {
            String modelRoot = gptSovitsConfig.getModelRoot();
            useRefAudio = modelRoot + "/" + cc.getRefAudio();
            usePromptText = cc.getPromptText() != null ? cc.getPromptText() : "";
            usePromptLang = cc.getPromptLang() != null ? cc.getPromptLang() : promptLang;
        }

        // 清理文本：去除 emoji、特殊符号、Markdown 标记
        String cleanedText = cleanTextForTTS(text);
        if (cleanedText.trim().isEmpty()) {
            throw new IllegalArgumentException("清理后文本为空");
        }

        // 限制文本长度，避免生成长文本耗时长
        String trimmedText = cleanedText.length() > 500 ? cleanedText.substring(0, 500) + "……" : cleanedText;

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(gptSovitsApiUrl + "/tts");
        httpPost.setHeader("Content-Type", "application/json");

        // 构建请求体 - 参考 GPT-SoVITS api_v2.py 的接口规范
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("text", trimmedText);
        requestBody.put("text_lang", textLang);
        requestBody.put("ref_audio_path", useRefAudio);
        // 训练好的模型传 prompt_text 帮助对齐音色
        requestBody.put("prompt_text", usePromptText);
        requestBody.put("prompt_lang", usePromptLang);
        requestBody.put("media_type", "wav");
        requestBody.put("streaming_mode", false);
        requestBody.put("speed_factor", 1.0);
        requestBody.put("text_split_method", "cut0"); // 不切分文本
        requestBody.put("split_bucket", true); // 打开分桶处理
        requestBody.put("parallel_infer", false); // 关闭并行执行推理
        requestBody.put("fragment_interval", 0.0); // 片段间不添加静音间隔

        httpPost.setEntity(new StringEntity(requestBody.toString(), StandardCharsets.UTF_8));

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
                throw new IOException("GPT-SoVITS 返回错误 (HTTP " + statusCode + "): " + sb);
            }

            String userIdStr = userId != null ? userId.toString() : "anonymous";

            // 确保输出目录存在：uploads/tts/{userId}/{date}
            File outDir = new File(outputDir, userIdStr);
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
            return "/uploads/tts/" + userIdStr + "/" + dateStr + "/" + fileName;
        } finally {
            httpClient.close();
        }
    }

    /**
     * 清理文本用于 TTS 合成
     * 去除 emoji、Markdown 符号、特殊标点等不适合语音合成的字符
     * 将 markdown 结构转为自然口语化文本，避免 TTS 断句混乱
     */
    private String cleanTextForTTS(String text) {
        String result = text;
        // 去除 Markdown 代码块
        result = result.replaceAll("```[\\s\\S]*?```", "");
        // 去除 HTML 标签
        result = result.replaceAll("<[^>]+>", "");
        // 去除 Markdown 链接，保留文本
        result = result.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
        // 去除 Markdown 目录标记 TOC（通常出现在开头）
        result = result.replaceAll("(?i)\\bTOC\\b", "");
        // 去除 Markdown 标题标记（# ## ### 等）
        result = result.replaceAll("#{1,6}\\s*", "");
        // 去除 Markdown 引用标记 >
        result = result.replaceAll("^>+\\s*", "");
        // 去除 Markdown 水平分割线
        result = result.replaceAll("^[-*_]{3,}\\s*$", "");
        // 去除 Markdown 加粗/斜体
        result = result.replaceAll("\\*{1,3}", "");
        result = result.replaceAll("_{1,3}", "");
        // 去除行内代码
        result = result.replaceAll("`[^`]+`", "");
        // 去除 Markdown 表格分隔符
        result = result.replaceAll("\\|", "，");

        // 按行处理：去除每行开头的列表标记，并将每行转为自然语句
        String[] lines = result.split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            // 去除行首列表标记：- * • 等
            trimmed = trimmed.replaceAll("^[\\-\\*•▪▫◦]\\s+", "");
            // 去除行首数字列表标记：1. 2. 3. 或 1) 2) 3)
            trimmed = trimmed.replaceAll("^\\d+[.)、]\\s*", "");
            // 去除行首括号编号 （1） (1) 【1】 等
            trimmed = trimmed.replaceAll("^[（(【\\[]\\s*\\d+\\s*[）)】\\]]\\s*", "");
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("。");
                }
                sb.append(trimmed);
            }
        }
        result = sb.toString();

        // 去除文本中间的数字列表标记（如 " 1. " " 2. " 等，空格分隔非换行）
        result = result.replaceAll("\\s+\\d+\\.\\s+", "，");
        // 去除文本中间的破折号列表标记（如 " - "）
        result = result.replaceAll("\\s+[\\-•▪▫◦]\\s+", "，");

        // 去除各类括号，保留内容
        result = result.replaceAll("[【】「」『』《》\\[\\]（）()]", "");
        // 斜杠替换为"或"（如"编号/选项" → "编号或选项"）
        result = result.replaceAll("[/\\\\]", "或");

        // ---- 标点符号统一：转为适合 TTS 的自然断句 ----
        // 顿号、分号 → 逗号（顿号会导致 TTS 断句过碎）
        result = result.replace("、", "，");
        result = result.replace(";", "，");
        result = result.replace("；", "，");
        // 冒号 → 逗号（冒号会导致 TTS 停顿过长或断句异常）
        result = result.replace(":", "，");
        result = result.replace("：", "，");
        // 连续问号/感叹号 → 单个（避免 TTS 语调过于夸张）
        result = result.replaceAll("[？?]{2,}", "？");
        result = result.replaceAll("[！!]{2,}", "！");
        // 省略号统一
        result = result.replaceAll("……+", "……");
        result = result.replaceAll("\\.{3,}", "……");
        // 多个连续逗号 → 单个逗号
        result = result.replaceAll("[，,]{2,}", "，");
        // 破折号 → 逗号
        result = result.replace("——", "，");
        result = result.replace("—", "，");

        // 去除 emoji 和特殊符号
        result = result.replaceAll("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{1F900}-\\x{1F9FF}\\x{2702}-\\x{27B0}\\x{1F100}-\\x{1F1FF}\\x{1F200}-\\x{1F2FF}\\x{2B00}-\\x{2BFF}]", "");
        // 去除特殊装饰符号
        result = result.replaceAll("[§¶†‡•◦▪▫►◀▲▼◆◇★☆※✦✧✩✪♬♫♪]", "");
        // 去除开头的标点符号
        result = result.replaceAll("^[\\s。，,.;:、！!？?·.·-]+", "");
        // 合并多余空格
        result = result.replaceAll("[\\t ]+", " ");
        // 合并连续句号
        result = result.replaceAll("[。]{2,}", "。");
        // 去除句末多余的逗号
        result = result.replaceAll("，+$", "。");
        result = result.trim();
        return result;
    }

    private Process gptSovitsProcess;

    public void startGptSovits() throws Exception {
        // 幂等性检查 1：进程引用仍存活
        if (gptSovitsProcess != null && gptSovitsProcess.isAlive()) {
            log.info("GPT-SoVITS 进程已存在（PID={}），跳过启动", gptSovitsProcess.pid());
            return;
        }
        // 幂等性检查 2：后端重启后进程引用丢失，但插件可能仍在运行（端口检测）
        if (processManager.isPortOpen("localhost", 8000)) {
            log.info("GPT-SoVITS 端口 8000 已监听，跳过启动（后端重启场景）");
            return;
        }

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
        // 方式 1：杀存储的进程引用
        if (gptSovitsProcess != null && gptSovitsProcess.isAlive()) {
            processManager.destroyProcess(gptSovitsProcess);
            gptSovitsProcess = null;
            log.info("GPT-SoVITS 进程引用已销毁");
        }
        // 方式 2：通过端口 8000 精确定位并杀掉（避免误杀其他 python 进程）
        processManager.killProcessByPort(8000, "GPT-SoVITS");
        log.info("GPT-SoVITS stopped");
    }
}
