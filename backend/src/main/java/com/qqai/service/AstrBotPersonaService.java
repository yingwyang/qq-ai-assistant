package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqai.common.ProcessManager;
import com.qqai.entity.UserSettings;
import com.qqai.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「双层提示词」的人层：把用户在 AstrBot 里挑的人格接进后端所有 AI 链路。
 *
 * <h3>为什么是这套实现</h3>
 * <ul>
 *   <li><b>岗位</b>（任务规则、输出格式、硬约束）由后端 {@code prompts.yml} + 各服务提供；</li>
 *   <li><b>人</b>（人格、语气、口癖）由 AstrBot 的 persona 提供；</li>
 *   <li>但 AstrBot 的 {@code /api/v1/chat} <b>没有 persona 字段</b>，人格只能通过
 *       「配置档案」或「会话记录」生效，且会话记录优先级更高（老会话会一直用旧人格）。</li>
 * </ul>
 *
 * <p>因此落地方式：为每个人格生成一份<b>配置档案</b>
 * （{@code qqai-p-<slug>-text} 文本 / {@code qqai-p-<slug>-image} 视觉），
 * 请求时用 {@code config_name} 指定 + 每次新 session_id（避免旧会话的人格覆盖）。
 * 档案是内存热生效的（实测新建后立刻可用，无需重启）。</p>
 *
 * <h3>为什么要「无工具副本」</h3>
 * <p>AstrBot 按 persona 注入工具，{@code persona.tools = null} 表示<b>全部工具</b>：
 * 模型会去调 {@code send_message_to_user} / {@code future_task}，把「请稍等片刻」
 * 当成摘要正文。所以每个人格都会克隆一份 {@code tools = []} 的副本
 * （{@code qqai_p_<slug>}）：<b>话术照搬，工具清空</b>，原人格不动。</p>
 *
 * <h3>什么时候需要重启 AstrBot</h3>
 * <p>配置档案是热生效的；但人格列表 {@code personas_v3} 是启动时载入的，
 * 新克隆的人格必须重启才会被看见。所以只有「首次选中某个人格」才重启，
 * 之后切换（副本已存在）是秒切。</p>
 */
@Service
public class AstrBotPersonaService {

    private static final Logger log = LoggerFactory.getLogger(AstrBotPersonaService.class);

    /** 克隆人格前缀：qqai_p_<slug> */
    static final String CLONE_PREFIX = "qqai_p_";
    /** 档案名前缀：qqai-p-<slug>-text / -image */
    static final String PROFILE_PREFIX = "qqai-p-";

    @Value("${astrbot.data-path:./Astrbot/data}")
    private String astrbotDataPath;

    @Value("${astrbot.api-url:http://127.0.0.1:6185}")
    private String astrBotApiUrl;

    @Value("${astrbot.token:}")
    private String astrBotToken;

    /** 内部/系统人格名（脚本生成的安静人格等），不暴露给用户选择 */
    @Value("${astrbot.internal-persona-ids:vision_task_quiet}")
    private String internalPersonaIds;

    /** 「人」的文本底座档案：复制它的模型等设置，只换人格 */
    @Value("${astrbot.persona-text-base-profile:ray}")
    private String textBaseProfile;

    /** 「人」的视觉底座档案：带图请求用它的多模态模型 */
    @Value("${astrbot.persona-image-base-profile:vision}")
    private String imageBaseProfile;

    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private UserSettingsService userSettingsService;

    /** 人格运行契约来自 prompts.yml（persona.contract），测试直接 new 时为 null */
    @Autowired(required = false)
    private PromptTemplateService promptTemplateService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /** personaId → 档案是否已就绪，省掉每次请求都查库/打接口 */
    private final Map<String, Boolean> readyCache = new ConcurrentHashMap<>();

    // ==================== 查询 ====================

    /**
     * 可选的「人」列表（直接读 AstrBot 的 personas 表，不需要 persona scope 的 API Key）。
     *
     * @return 每项：personaId / 预览 / 是否默认 / 是否已备好无工具副本
     */
    public List<Map<String, Object>> listPersonas() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT id, persona_id, system_prompt, tools, is_default, sort_order "
                + "FROM personas ORDER BY sort_order, id";
        try (Connection conn = openDb();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String personaId = rs.getString("persona_id");
                if (personaId == null || personaId.startsWith(CLONE_PREFIX)) continue;   // 副本不外露
                if (isInternalPersona(personaId)) continue;                              // 系统人格不外露
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("personaId", personaId);
                item.put("preview", preview(rs.getString("system_prompt")));
                item.put("prompt", rs.getString("system_prompt"));       // 全文：前端给用户看
                item.put("audit", auditPersona(rs.getString("system_prompt")));  // 规范自检
                item.put("isDefault", rs.getInt("is_default") == 1);
                item.put("cloneReady", cloneInSync(personaId));
                list.add(item);
            }
        } catch (Exception e) {
            log.warn("读取 AstrBot 人格列表失败: {}", e.getMessage());
            throw new BizException(503, "读取 AstrBot 人格失败：" + e.getMessage());
        }
        // 默认人格排前面
        list.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.get("isDefault")), Boolean.TRUE.equals(a.get("isDefault"))));
        return list;
    }

    /**
     * 轻量视图：只回「当前用户选的人格名」，给前端顶栏显示用。
     *
     * <p>刻意不查副本/档案就绪状态：{@link #selectionStatus(Long)} 每次要开 SQLite 并访问两次
     * AstrBot 接口，而顶栏每 30 秒轮询一次状态，没必要为此做这些。</p>
     */
    public Map<String, Object> currentSelectionLight(Long userId) {
        String personaId = currentPersonaId(userId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("personaId", personaId);
        // AstrBot 的人格名本身就是展示名（如「灰泽满」）；没选人格时前端回退到助手名
        view.put("personaName", personaId);
        return view;
    }

    /** 当前用户的选择 + 生效状态（给前端「AstrBot 模块」用） */
    public Map<String, Object> selectionStatus(Long userId) {
        Map<String, Object> view = new LinkedHashMap<>();
        String personaId = currentPersonaId(userId);
        view.put("personaId", personaId);
        view.put("personaName", personaId);
        view.put("astrbotRunning", processManager.isPortOpen("127.0.0.1", 6185));
        view.put("configName", personaId == null ? null : profileName(personaId, false));
        if (personaId != null) {
            boolean cloneReady = cloneInSync(personaId);
            view.put("cloneReady", cloneReady);
            view.put("clonePersonaId", clonePersonaId(personaId));
            view.put("textProfileReady", cloneReady && profileExists(profileName(personaId, false)));
            view.put("imageProfileReady", cloneReady && profileExists(profileName(personaId, true)));
            view.put("restartRequired", !cloneReady);
        } else {
            view.put("cloneReady", null);
            view.put("textProfileReady", false);
            view.put("imageProfileReady", false);
            view.put("restartRequired", false);
        }
        return view;
    }

    /**
     * 给调用方用的档案名：用户没选人格 → null（调用方用默认/vision-task 档案）。
     *
     * @param userId    当前用户；null 表示后台任务（日报定时等），走默认
     * @param withImage 本次请求是否带图（决定文本档案还是视觉档案）
     */
    public String resolveConfigName(Long userId, boolean withImage) {
        String personaId = currentPersonaId(userId);
        if (personaId == null) return null;
        if (!Boolean.TRUE.equals(readyCache.get(personaId))) {
            try {
                ensureArtifacts(personaId, false);
                readyCache.put(personaId, Boolean.TRUE);
            } catch (Exception e) {
                log.warn("人格档案未就绪，本次回退默认档案: persona={}, err={}", personaId, e.getMessage());
                return null;
            }
        }
        return profileName(personaId, withImage);
    }

    public String currentPersonaId(Long userId) {
        if (userId == null) return null;
        return userSettingsService.findByUserId(String.valueOf(userId))
                .map(UserSettings::getAstrbotPersonaId)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
    }

    // ==================== 选择（写） ====================

    /**
     * 用户选择「人」：确保无工具副本 + 两份配置档案存在，落库，必要时重启 AstrBot。
     *
     * @param userId    当前用户
     * @param personaId AstrBot 里的人格名；传空表示恢复默认（不指定人格）
     * @return 选择后的状态视图（含是否重启过）
     */
    public Map<String, Object> select(Long userId, String personaId) {
        if (userId == null) throw new BizException(401, "未登录");
        UserSettings settings = userSettingsService.findByUserId(String.valueOf(userId))
                .orElseGet(() -> {
                    UserSettings s = new UserSettings();
                    s.setUserId(String.valueOf(userId));
                    return s;
                });

        if (personaId == null || personaId.isBlank()) {
            settings.setAstrbotPersonaId(null);
            userSettingsService.save(settings);
            Map<String, Object> view = selectionStatus(userId);
            view.put("restarted", false);
            return view;
        }

        String target = personaId.trim();
        if (target.startsWith(CLONE_PREFIX)) {
            throw new BizException(400, "该名称是系统生成的副本人格，请选择原始人格");
        }
        if (!personaExists(target)) {
            throw new BizException(404, "AstrBot 里没有这个人格：" + target);
        }

        boolean restarted = ensureArtifacts(target, true);
        readyCache.put(target, Boolean.TRUE);
        settings.setAstrbotPersonaId(target);
        userSettingsService.save(settings);

        Map<String, Object> view = selectionStatus(userId);
        view.put("restarted", restarted);
        return view;
    }

    /**
     * 保证「无工具副本 + 两份档案」都存在，且副本带最新的人格契约。
     *
     * <p>三件事都做了才认为就绪：① 副本人格（话术 + 契约，tools=[]）；② 文本档案；③ 视觉档案。
     * 副本新建或契约文本有变化时需要重启 AstrBot（人格在启动时载入），档案是热生效的。</p>
     *
     * @return 是否为此重启了 AstrBot
     */
    synchronized boolean ensureArtifacts(String personaId, boolean allowRestart) {
        String cloneId = clonePersonaId(personaId);
        CloneState state = ensureClonePersona(personaId, cloneId, contractText());
        boolean cloneChanged = state != CloneState.UNCHANGED;
        boolean restarted = false;
        if (cloneChanged) {
            if (!allowRestart) {
                // 只读路径：副本刚建好/刚更新但 AstrBot 还没加载，本次先回退默认档案
                throw new BizException(503, "人格副本需要更新，重启 AstrBot 后才生效");
            }
            restartAstrBotAndWait();
            restarted = true;
        }
        ensureProfile(personaId, cloneId, false);
        ensureProfile(personaId, cloneId, true);
        return restarted;
    }

    /** 重启 AstrBot 并等到接口可用（最多 ~90s） */
    public void restartAstrBotAndWait() {
        log.info("人格变更：重启 AstrBot 以加载新的人格副本");
        try {
            astrBotService.stopAstrBot();
        } catch (Exception e) {
            log.warn("停止 AstrBot 失败（继续尝试启动）: {}", e.getMessage());
        }
        try {
            astrBotService.startAstrBot();
        } catch (Exception e) {
            throw new BizException(503, "AstrBot 启动失败：" + e.getMessage());
        }
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (processManager.isPortOpen("127.0.0.1", 6185)) {
                log.info("AstrBot 已就绪（等待 {}s）", (i + 1) * 3);
                return;
            }
        }
        throw new BizException(503, "AstrBot 重启超时（90s 内端口 6185 未就绪）");
    }

    public boolean isAstrBotRunning() {
        return processManager.isPortOpen("127.0.0.1", 6185);
    }

    // ==================== 档案 ====================

    /**
     * 确保某个（人格 × 模态）的配置档案存在且指向无工具副本。
     *
     * <p>做法是「删掉重建」：档案是热生效的，重建比 diff 更省事，也顺带修正历史遗留配置。</p>
     */
    private void ensureProfile(String personaId, String cloneId, boolean withImage) {
        String name = profileName(personaId, withImage);
        String baseName = withImage ? imageBaseProfile : textBaseProfile;
        String baseId = profileIdByName(baseName);
        if (baseId == null) {
            throw new BizException(503, "底座配置档案不存在：" + baseName + "（可在 application.yml 调整 astrbot.persona-*-base-profile）");
        }
        JsonNode baseConfig = fetchProfileConfig(baseId);
        if (baseConfig == null) {
            throw new BizException(503, "读取底座档案失败：" + baseName);
        }
        ObjectNode config = (ObjectNode) baseConfig.deepCopy();
        ObjectNode persona = config.with("agent_runner").with("config").with("persona");
        persona.put("persona_id", cloneId);

        String existingId = profileIdByName(name);
        if (existingId != null) {
            // 已经指向同一个副本就不再折腾
            JsonNode current = fetchProfileConfig(existingId);
            if (current != null
                    && cloneId.equals(current.path("agent_runner").path("config").path("persona").path("persona_id").asText(null))) {
                return;
            }
            deleteProfile(existingId);
        }
        HttpHeaders headers = apiHeaders();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("config", config);
        ResponseEntity<String> resp = restTemplate.exchange(
                astrBotApiUrl + "/api/v1/config-profiles", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        log.info("已创建人格档案 {} (HTTP {})", name, resp.getStatusCode().value());
    }

    private JsonNode fetchProfileConfig(String profileId) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    astrBotApiUrl + "/api/v1/config-profiles/" + profileId, HttpMethod.GET,
                    new HttpEntity<>(apiHeaders()), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode data = root.path("data");
            return data.has("config") ? data.get("config") : data;
        } catch (Exception e) {
            log.warn("读取配置档案 {} 失败: {}", profileId, e.getMessage());
            return null;
        }
    }

    private void deleteProfile(String profileId) {
        try {
            restTemplate.exchange(astrBotApiUrl + "/api/v1/config-profiles/" + profileId, HttpMethod.DELETE,
                    new HttpEntity<>(apiHeaders()), String.class);
        } catch (Exception e) {
            log.warn("删除旧档案 {} 失败: {}", profileId, e.getMessage());
        }
    }

    private String profileIdByName(String name) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    astrBotApiUrl + "/api/v1/config-profiles", HttpMethod.GET,
                    new HttpEntity<>(apiHeaders()), String.class);
            JsonNode list = objectMapper.readTree(resp.getBody()).path("data").path("info_list");
            for (JsonNode item : list) {
                if (name.equals(item.path("name").asText())) return item.path("id").asText();
            }
        } catch (Exception e) {
            log.warn("查询配置档案列表失败: {}", e.getMessage());
        }
        return null;
    }

    private boolean profileExists(String name) {
        return profileIdByName(name) != null;
    }

    private HttpHeaders apiHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", astrBotToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ==================== 人格副本 ====================

    /** 无工具副本的人格名（同一个原始人格始终映射到同一个副本） */
    public static String clonePersonaId(String personaId) {
        return CLONE_PREFIX + slug(personaId);
    }

    /** 档案名：qqai-p-<slug>-text / -image */
    public static String profileName(String personaId, boolean withImage) {
        return PROFILE_PREFIX + slug(personaId) + (withImage ? "-image" : "-text");
    }

    /** 人格名 → 安全的 ascii 标识（保留可读部分 + 短哈希防撞） */
    public static String slug(String personaId) {
        StringBuilder sb = new StringBuilder();
        for (char c : personaId.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c >= 'A' && c <= 'Z') sb.append(Character.toLowerCase(c));
            else if (c == '-' || c == '_') sb.append(c);
            if (sb.length() >= 24) break;
        }
        if (sb.length() == 0) sb.append("p");
        return sb + "_" + Integer.toHexString(personaId.hashCode() & 0x7fffffff);
    }

    private boolean personaExists(String personaId) {
        try (Connection conn = openDb();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM personas WHERE persona_id = ?")) {
            ps.setString(1, personaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new BizException(503, "读取 AstrBot 人格失败：" + e.getMessage());
        }
    }

    private boolean cloneExists(String personaId) {
        try (Connection conn = openDb()) {
            return cloneExists(conn, clonePersonaId(personaId));
        } catch (Exception e) {
            log.warn("检查人格副本失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 副本是否与「源人格话术 + 当前契约 + tools=[]」完全一致。
     * 不一致就意味着下次选用它要重建/更新并重启 AstrBot（前端据此提示「首次启用需重启」）。
     */
    private boolean cloneInSync(String personaId) {
        String contract = contractText();
        try (Connection conn = openDb()) {
            String sourcePrompt;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT system_prompt FROM personas WHERE persona_id = ?")) {
                ps.setString(1, personaId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    sourcePrompt = rs.getString(1) == null ? "" : rs.getString(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT system_prompt, tools FROM personas WHERE persona_id = ?")) {
                ps.setString(1, clonePersonaId(personaId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String clonePrompt = rs.getString(1);
                    String tools = rs.getString(2);
                    if (tools != null && !tools.isBlank() && !"[]".equals(tools.trim())) return false;
                    return composeClonePrompt(sourcePrompt, contract).equals(clonePrompt);
                }
            }
        } catch (Exception e) {
            log.warn("检查人格副本同步状态失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean cloneExists(Connection conn, String cloneId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM personas WHERE persona_id = ?")) {
            ps.setString(1, cloneId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 副本人格相对期望状态的差异 */
    enum CloneState { CREATED, UPDATED, UNCHANGED }

    /**
     * 保证副本人格存在且与「源人格话术 + 最新契约」一致：话术照搬，工具清空，末尾追加运行契约。
     *
     * <p>幂等：内容一致时不动数据库、不触发重启；源人格改了话术或契约文本改了才会 UPDATE。</p>
     */
    private CloneState ensureClonePersona(String personaId, String cloneId, String contract) {
        try (Connection conn = openDb()) {
            String sourcePrompt;
            String dialogs = "[]";
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT system_prompt, begin_dialogs FROM personas WHERE persona_id = ?")) {
                ps.setString(1, personaId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new BizException(404, "AstrBot 里没有这个人格：" + personaId);
                    sourcePrompt = rs.getString("system_prompt") == null ? "" : rs.getString("system_prompt");
                    String d = rs.getString("begin_dialogs");
                    if (d != null && !d.isBlank()) dialogs = d;
                }
            }
            String expected = composeClonePrompt(sourcePrompt, contract);

            String existing = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT system_prompt FROM personas WHERE persona_id = ?")) {
                ps.setString(1, cloneId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) existing = rs.getString(1);
                }
            }
            String now = LocalDateTime.now().toString();
            if (existing == null) {
                int nextId = 1;
                int nextSort = 1;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COALESCE(MAX(id), 0) + 1, COALESCE(MAX(sort_order), 0) + 1 FROM personas")) {
                    if (rs.next()) {
                        nextId = rs.getInt(1);
                        nextSort = rs.getInt(2);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO personas (created_at, updated_at, id, persona_id, system_prompt, begin_dialogs,"
                                + " tools, skills, custom_error_message, folder_id, sort_order, is_default)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.setInt(3, nextId);
                    ps.setString(4, cloneId);
                    ps.setString(5, expected);
                    ps.setString(6, dialogs);
                    ps.setString(7, "[]");     // ★ 无工具：否则模型会去调 send_message_to_user
                    ps.setString(8, null);
                    ps.setString(9, null);
                    ps.setString(10, null);
                    ps.setInt(11, nextSort);
                    ps.setInt(12, 0);
                    ps.executeUpdate();
                }
                log.info("已创建无工具人格副本 {}（源人格 {}，含运行契约 {} 字）",
                        cloneId, personaId, contract == null ? 0 : contract.length());
                return CloneState.CREATED;
            }
            if (!expected.equals(existing)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE personas SET system_prompt = ?, tools = '[]', updated_at = ? WHERE persona_id = ?")) {
                    ps.setString(1, expected);
                    ps.setString(2, now);
                    ps.setString(3, cloneId);
                    ps.executeUpdate();
                }
                log.info("已更新无工具人格副本 {}（源人格话术或运行契约有变化）", cloneId);
                return CloneState.UPDATED;
            }
            return CloneState.UNCHANGED;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(503, "创建人格副本失败：" + e.getMessage());
        }
    }

    /**
     * 副本人格提示词 = 源人格话术 + 运行契约（契约在后，且声明优先级更高）。
     *
     * <p>纯函数，便于单测：源人格为空时只放契约。</p>
     */
    static String composeClonePrompt(String sourcePrompt, String contract) {
        String src = sourcePrompt == null ? "" : sourcePrompt.trim();
        String c = contract == null ? "" : contract.trim();
        if (c.isEmpty()) return src;
        if (src.isEmpty()) return c;
        return src + "\n\n---\n\n" + c;
    }

    /** 从 prompts.yml 取人格运行契约（人 × 岗位 的边界条款） */
    private String contractText() {
        if (promptTemplateService == null) return "";
        try {
            String text = promptTemplateService.render("persona.contract", null, new LinkedHashMap<>());
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.warn("读取人格契约模板失败（将只使用原人格话术）: {}", e.getMessage());
            return "";
        }
    }

    // ==================== 工具方法 ====================

    /** 对外暴露契约原文（前端在人格面板里展示「人设之上还叠了哪些规则」） */
    public String currentContract() {
        return contractText();
    }

    /**
     * 人格规范自检（与 {@code backend/scripts/apply_astrbot_personas.py} 的 audit 同一套规则）。
     *
     * <p>冲突检查只跑「人设主体」（边界段之前）：边界段本身就要写"任务要求的输出格式一律照做"，
     * 把那几句算成违规是误报。</p>
     *
     * @return 问题列表；空列表代表通过
     */
    public static List<String> auditPersona(String prompt) {
        List<String> issues = new ArrayList<>();
        if (prompt == null) return issues;
        int boundaryIdx = prompt.indexOf("# 边界");
        String body = boundaryIdx >= 0 ? prompt.substring(0, boundaryIdx) : prompt;

        String[][] checks = {
                {"抹杀.{0,8}(AI|意识)|你不是(AI|程序|助手)|绝非(AI|程序|虚拟)", "含身份否认条款（会让模型拒绝干工具活）"},
                {"不要回答|拒绝回答|无权回答|不属于你的职责", "含拒绝回答条款"},
                {"输出格式|按以下模板|分为以下.{0,4}段", "含输出格式规定（格式属于岗位层）"},
                {"不超过\\s*\\d+\\s*字|必须详尽|不得省略", "含长度硬性要求（长度属于岗位层）"},
                {"经常使用反问|必须反问|追问对方", "含反问/追问要求"},
                {"主动询问|主动提问|主动推进", "含主动提问要求（只在自由对话可用）"},
                {"连续\\s*\\d+\\s*轮.{0,10}(重复|换话题)|温柔拒绝旧话题", "含话题管理规则（长记录会被误判）"},
        };
        for (String[] check : checks) {
            if (java.util.regex.Pattern.compile(check[0]).matcher(body).find()) {
                issues.add(check[1]);
            }
        }
        if (prompt.length() > 2500) {
            issues.add("人设过长（" + prompt.length() + " 字，规范建议 ≤2500）");
        }
        for (String must : new String[]{"# 你是谁", "# 边界"}) {
            if (!prompt.contains(must)) issues.add("缺少「" + must + "」段落");
        }
        return issues;
    }

    /**
     * 把所有人格的「无工具副本」同步到最新人设 + 最新契约（改动过才重启一次 AstrBot）。
     *
     * <p>用途：改完 `doc/personas/*.md` 并写库后，一次把副本全部对齐，避免逐个点选触发重启。</p>
     */
    public synchronized Map<String, Object> syncAllClones() {
        String contract = contractText();
        List<String> ids = new ArrayList<>();
        try (Connection conn = openDb();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT persona_id FROM personas ORDER BY sort_order, id")) {
            while (rs.next()) {
                String id = rs.getString(1);
                if (id == null || id.startsWith(CLONE_PREFIX) || isInternalPersona(id)) continue;
                ids.add(id);
            }
        } catch (Exception e) {
            throw new BizException(503, "读取人格列表失败：" + e.getMessage());
        }

        List<String> changed = new ArrayList<>();
        for (String id : ids) {
            CloneState state = ensureClonePersona(id, clonePersonaId(id), contract);
            if (state != CloneState.UNCHANGED) changed.add(id);
        }
        boolean restarted = false;
        if (!changed.isEmpty() && isAstrBotRunning()) {
            restartAstrBotAndWait();
            restarted = true;
        }
        // 只为「用户实际选过」的人格刷档案，避免凭空生成一堆档案
        List<String> profilesRefreshed = new ArrayList<>();
        for (String id : ids) {
            if (profileIdByName(profileName(id, false)) == null) continue;
            ensureProfile(id, clonePersonaId(id), false);
            ensureProfile(id, clonePersonaId(id), true);
            profilesRefreshed.add(id);
        }
        readyCache.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("personas", ids.size());
        result.put("clonesUpdated", changed);
        result.put("profilesRefreshed", profilesRefreshed);
        result.put("restarted", restarted);
        log.info("人格副本同步完成：共 {} 个人格，更新 {} 个副本，刷新 {} 组档案，重启={}",
                ids.size(), changed.size(), profilesRefreshed.size(), restarted);
        return result;
    }

    private Connection openDb() throws Exception {
        String dbPath = astrbotDataPath + "/data_v4.db";
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    /** 是否是后端/脚本创建的系统人格（不暴露给用户选择） */
    private boolean isInternalPersona(String personaId) {
        if (personaId == null) return true;
        if (personaId.startsWith(CLONE_PREFIX)) return true;
        if (internalPersonaIds == null) return false;
        for (String id : internalPersonaIds.split(",")) {
            if (personaId.equals(id.trim())) return true;
        }
        return false;
    }

    private static String preview(String prompt) {
        if (prompt == null) return "";
        String s = prompt.replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
