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

    /**
     * TTS 合成互斥锁。
     *
     * <p>GPT-SoVITS 的 /set_gpt_weights、/set_sovits_weights 是**服务端全局状态**，
     * 而「切换权重 → 调用 /tts 合成」是两步。若两个用户并发要不同音色：
     * A 切到 Eustia → B 切到 Kisaki → A 的合成请求用的是 Kisaki 的权重（声音串台）。
     * switchCharacter 上的 synchronized 只保证切换本身互斥，挡不住这个跨步竞态，
     * 所以这里把「解析角色 → 切换 → 合成 → 落盘」整段串行化。</p>
     *
     * <p>代价：TTS 请求被串行处理（单次约 1~5 秒）。对本项目这种单实例、
     * 低频语音合成的场景是正确性优先的合理取舍。</p>
     */
    private final java.util.concurrent.locks.ReentrantLock ttsLock = new java.util.concurrent.locks.ReentrantLock();

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

    /** GPT-SoVITS 安装目录（v2Pro / v5 均可；为空则回退到旧硬编码路径） */
    @Value("${gpt-sovits.install-dir:}")
    private String installDir;

    /** 启动时使用的推理配置（相对安装目录）；v5 必须显式指定，否则用默认 v2 配置 */
    @Value("${gpt-sovits.infer-config:}")
    private String inferConfig;

    /** 是否开启分桶处理（默认关闭：实测长文本会提前截断） */
    @Value("${gpt-sovits.split-bucket:false}")
    private boolean splitBucket;

    /** 语速系数（v5turbo 原生模型需要约 1.4 校准；v2Pro 权重保持 1.0） */
    @Value("${gpt-sovits.speed-factor:1.0}")
    private double speedFactor;

    /** 是否启用合成缓存（同一角色 + 同一文本复用已合成音频） */
    @Value("${gpt-sovits.cache-enabled:true}")
    private boolean cacheEnabled;

    /** 单次合成最大字数；超过则按句切分再拼接。&lt;=0 表示不分片 */
    @Value("${gpt-sovits.chunk-max-chars:400}")
    private int chunkMaxChars;

    /** 分片之间的静音间隔（毫秒），0 表示不留间隔 */
    @Value("${gpt-sovits.fragment-interval-ms:120}")
    private int fragmentIntervalMs;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private GptSovitsConfig gptSovitsConfig;

    /**
     * 长耗时推理专用客户端（连接 10s / 读取 5min，见 HttpClientConfig）。
     * 语音合成属重推理，等待时间放宽但仍有上界；共享实例，服务内不得 close。
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("longRunningHttpClient")
    private CloseableHttpClient httpClient;

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
        HttpGet httpGet = new HttpGet(fullUrl);
        try (CloseableHttpResponse resp = httpClient.execute(httpGet)) {
            int code = resp.getCode();
            if (code != 200) {
                String body = new String(resp.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("切换权重失败 HTTP " + code + ": " + body);
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
        ttsLock.lock();
        try {
            return generateVoiceLocked(text, userId, characterName);
        } finally {
            ttsLock.unlock();
        }
    }

    /**
     * generateVoice 的实际实现；**调用方必须持有 ttsLock**（见该字段注释）。
     */
    private String generateVoiceLocked(String text, Long userId, String characterName) throws Exception {
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
        // 每个角色可以有自己的合成语言：日文角色用 ja，中文角色用 zh。
        // 用全局 text-lang（默认 zh）会把日文当中文处理，音频明显变短、发音错乱。
        String useTextLang = textLang;
        if (cc != null) {
            String modelRoot = gptSovitsConfig.getModelRoot();
            useRefAudio = modelRoot + "/" + cc.getRefAudio();
            usePromptText = cc.getPromptText() != null ? cc.getPromptText() : "";
            usePromptLang = cc.getPromptLang() != null ? cc.getPromptLang() : promptLang;
            if (cc.getTextLang() != null && !cc.getTextLang().isBlank()) {
                useTextLang = cc.getTextLang();
            }
        }

        // 清理文本：去除 emoji、特殊符号、Markdown 标记
        String cleanedText = cleanTextForTTS(text);
        if (cleanedText.trim().isEmpty()) {
            throw new IllegalArgumentException("清理后文本为空");
        }

        String fullText = cleanedText;
        String userIdStr = userId != null ? userId.toString() : "anonymous";
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        File userDir = new File(outputDir, userIdStr);

        // ---- 缓存：同一角色 + 同一文本只合成一次 ----
        // 文件按内容寻址，且放在该用户目录下，保证 MediaAccessFilter 的所有权校验仍成立
        // 缓存 key 必须包含「会改变音频的所有参数」：角色、权重文件、合成语言、语速。
        // 只按角色+文本做 key 会命中历史坏音频（例如修好 text_lang 后仍返回旧结果）。
        String cacheKey = sha256Hex(String.join("|",
                targetChar == null ? "default" : targetChar,
                cc == null ? "" : String.valueOf(cc.getGptPath()),
                cc == null ? "" : String.valueOf(cc.getSovitsPath()),
                useTextLang, String.valueOf(speedFactor),
                String.valueOf(chunkMaxChars), String.valueOf(splitBucket), fullText));
        File cacheDir = new File(userDir, "cache");
        File cacheFile = new File(cacheDir, cacheKey + ".wav");
        if (cacheEnabled && cacheFile.isFile() && cacheFile.length() > 0) {
            log.info("TTS 命中缓存 key={} 字数={}", cacheKey.substring(0, 8), fullText.length());
            return "/uploads/tts/" + userIdStr + "/cache/" + cacheKey + ".wav";
        }

        // ---- 长文本分片：超过阈值才切，按句末标点切分，逐段合成后拼接 ----
        List<String> segments = TtsAudioUtil.splitIntoChunks(fullText, chunkMaxChars);
        List<byte[]> parts = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            if (segments.size() > 1) {
                log.info("TTS 合成片段 {}/{}（{} 字）", i + 1, segments.size(), segments.get(i).length());
            }
            parts.add(synthesize(segments.get(i), useRefAudio, usePromptText, usePromptLang, useTextLang));
        }
        byte[] audio = parts.size() == 1 ? parts.get(0) : TtsAudioUtil.concatWav(parts, fragmentIntervalMs);

        // 缓存开启时统一按内容寻址存储并返回该 URL：
        // 同一文本第一次与后续调用拿到的是同一个地址，前端也能直接复用。
        if (cacheEnabled) {
            try {
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    throw new IOException("无法创建 TTS 缓存目录");
                }
                Files.write(cacheFile.toPath(), audio);
                return "/uploads/tts/" + userIdStr + "/cache/" + cacheKey + ".wav";
            } catch (Exception e) {
                log.warn("写入 TTS 缓存失败，回退为按日期存储: {}", e.getMessage());
            }
        }

        File dateDir = new File(userDir, dateStr);
        if (!dateDir.exists() && !dateDir.mkdirs()) {
            throw new IOException("无法创建 TTS 输出目录: " + dateDir.getAbsolutePath());
        }
        String fileName = UUID.randomUUID().toString() + ".wav";
        File outFile = new File(dateDir, fileName);
        Files.write(outFile.toPath(), audio);
        return "/uploads/tts/" + userIdStr + "/" + dateStr + "/" + fileName;
    }

    /**
     * 单段合成：调用 GPT-SoVITS /tts，返回 wav 字节（不分片、不落盘）。
     */
    private byte[] synthesize(String text, String refAudio, String promptText, String promptLangForRequest,
                              String textLangForRequest) throws Exception {
        // 共享的长超时客户端：带连接/读取超时（原先 HttpClients.createDefault() 无超时，
        // 每次调用还会新建一个连接池且 close 掉，既无上界又浪费）
        HttpPost httpPost = new HttpPost(gptSovitsApiUrl + "/tts");
        httpPost.setHeader("Content-Type", "application/json");

        // 构建请求体 - 参考 GPT-SoVITS api_v2.py 的接口规范
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("text", text);
        requestBody.put("text_lang", textLangForRequest);
        requestBody.put("ref_audio_path", refAudio);
        // 训练好的模型传 prompt_text 帮助对齐音色
        requestBody.put("prompt_text", promptText);
        requestBody.put("prompt_lang", promptLangForRequest);
        requestBody.put("media_type", "wav");
        requestBody.put("streaming_mode", false);
        requestBody.put("speed_factor", speedFactor);
        requestBody.put("text_split_method", "cut0"); // 不切分文本
        // 分桶会把文本按长度切成桶并行推理；实测（2026-10-20）开启时长文本
        // 经常提前结束、音频明显短于文本，按用户要求默认关闭。
        requestBody.put("split_bucket", splitBucket);
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

            try (InputStream is = response.getEntity().getContent()) {
                return is.readAllBytes();
            }
        }
    }

    /** 内容寻址用的 SHA-256 十六进制（取前 32 位足够避免碰撞） */
    private static String sha256Hex(String input) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, 32);
    }

    /**
     * 清理文本用于 TTS 合成
     * 去除 emoji、Markdown 符号、特殊标点等不适合语音合成的字符
     * 将 markdown 结构转为自然口语化文本，避免 TTS 断句混乱
     */
    String cleanTextForTTS(String text) {
        // HTML 实体必须最先解码：否则下面的 `#` 规则会把 &#91; 切成 &91;
        String result = decodeHtmlEntities(text);
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

        // 项目符号（- * +）在句中出现时必须去掉：GPT-SoVITS 会把 '-' 读成「减」
        // （线上日志实证：'群聊速览，- 近期主题' → 前端处理后成 '群聊速览,减近期主题'）
        result = result.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        result = result.replaceAll("([，。！？；：,])\\s*[-*+]\\s+", "$1");
        // 去除引号（TTS 会把引号读成停顿或杂音；英文直引号/中文弯引号都要去）
        result = result.replaceAll("[\"'\u201C\u201D\u2018\u2019]", "");
        // 项目符号 · 转为逗号（比直接删掉更保停顿）
        result = result.replace("·", "，");
        // 工具调用信封残留（<toolcall…>、被去符号后只剩 toolcall 开头的形态）：
        // 不清掉会被读成 "toolcall"
        result = result.replaceAll("(?i)</?(?:tool_?call|function_call)[^>]*>", "");
        result = result.replaceAll("(?i)^\\s*(?:tool_?call|function_call)\\b[\\s:：-]*", "");
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
        // 「。，」「！，」「？，」这类连用：去掉后面的逗号（否则 TTS 会在句末多停一拍）
        result = result.replaceAll("([。！？；])[，、]", "$1");
        // 「，。」这类反序连用：保留句号
        result = result.replaceAll("[，、]([。！？；])", "$1");
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

    /**
     * 解码常见 HTML 实体（数字实体 + 少量命名实体）。
     * 消息内容来自 Web 渲染链路，常带 &#91;TOC&#93; 这类残留。
     */
    private String decodeHtmlEntities(String text) {
        String decoded = text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d+);").matcher(decoded);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) cp)));
        }
        m.appendTail(sb);
        return sb.toString();
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
            // 安装目录走配置（instances: v2Pro / v5），未配置时回退到旧硬编码路径
            String gptSovitsPath = (installDir == null || installDir.isBlank())
                    ? projectRoot + File.separator + ".." + File.separator + ".." + File.separator + "GPT-SoVITS-v5"
                    : installDir;
            File gptSovitsDir = new File(gptSovitsPath).getCanonicalFile();
            String pythonPath = gptSovitsDir.getAbsolutePath() + File.separator + "runtime" + File.separator + "python.exe";
            String apiScriptPath = gptSovitsDir.getAbsolutePath() + File.separator + "api_v2.py";

            File pythonFile = new File(pythonPath);
            if (!pythonFile.exists()) {
                throw new IOException("GPT-SoVITS python.exe not found at: " + pythonPath);
            }

            // 注意：不能用 python -I。v5 的 api_v2 依赖脚本目录下的 tools 包，
            // 隔离模式不会把脚本目录加入 sys.path，会直接 ModuleNotFoundError。
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("cmd.exe"); cmd.add("/c"); cmd.add("start"); cmd.add("cmd.exe"); cmd.add("/k");
            cmd.add(pythonPath);
            cmd.add(apiScriptPath);
            if (inferConfig != null && !inferConfig.isBlank()) {
                cmd.add("-c");
                cmd.add(inferConfig);
            }
            cmd.add("-a"); cmd.add("127.0.0.1"); cmd.add("-p"); cmd.add("8000");
            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
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
