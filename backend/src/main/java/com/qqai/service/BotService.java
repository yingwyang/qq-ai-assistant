package com.qqai.service;

import com.qqai.config.BotProperties;
import com.qqai.entity.Bot;
import com.qqai.entity.User;
import com.qqai.exception.BizException;
import com.qqai.repository.BotRepository;
import com.qqai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 多机器人基础服务：创建/查询/改名/删除；运行时编排在后续阶段接入。 */
@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final BotRepository botRepository;
    private final UserRepository userRepository;
    private final BotProperties properties;
    private final AuditLogService auditLogService;

    public BotService(BotRepository botRepository, UserRepository userRepository,
                      BotProperties properties, AuditLogService auditLogService) {
        this.botRepository = botRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.auditLogService = auditLogService;
    }

    /** 首次启动时把现有单机器人登记为 id 最小的默认机器人，保证老数据有归属。 */
    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultBot() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!properties.isSeedDefault() || botRepository.count() > 0) {
            backfillExistingBots();
            return;
        }
        Long ownerId = resolveDefaultOwner();
        if (ownerId == null) {
            log.warn("跳过默认机器人种子：没有可用的 ADMIN 用户");
            backfillExistingBots();
            return;
        }
        Bot bot = new Bot();
        bot.setOwnerUserId(ownerId);
        bot.setName(properties.getDefaultName());
        bot.setQqNumber(blankToNull(properties.getDefaultQq()));
        bot.setStatus("STOPPED");
        bot.setRuntimeType("LOCAL");
        bot.setAgentToken(newToken());
        bot.setNapcatPort(properties.getDefaultNapcatPort());
        bot.setOnebotPort(properties.getDefaultOnebotPort());
        bot.setOnebotToken(newToken());
        bot.setWorkdir(resolvePath(properties.getDefaultWorkdir()).toString());
        try {
            Files.createDirectories(resolvePath(properties.getDefaultWorkdir()));
        } catch (IOException e) {
            log.warn("创建默认机器人工作目录失败: {}", e.getMessage());
        }
        botRepository.save(bot);
        auditLogService.log("SYSTEM", "BOT_SEED_DEFAULT", String.valueOf(bot.getId()), "SUCCESS",
                "登记默认机器人 qq=" + bot.getQqNumber() + " ports=" + bot.getNapcatPort() + "/" + bot.getOnebotPort());
        log.info("已登记默认机器人 id={} qq={}", bot.getId(), bot.getQqNumber());
        backfillExistingBots();
    }

    /** 兼容 P1 之前创建的 bots 行：补齐 runtimeType 与 agent token。 */
    private void backfillExistingBots() {
        for (Bot bot : botRepository.findAll()) {
            boolean changed = false;
            if (bot.getRuntimeType() == null || bot.getRuntimeType().isBlank()) {
                bot.setRuntimeType("LOCAL");
                changed = true;
            }
            if (bot.getAgentToken() == null || bot.getAgentToken().isBlank()) {
                bot.setAgentToken(newToken());
                changed = true;
            }
            if (changed) {
                botRepository.save(bot);
                log.info("补齐机器人运行时字段 id={} runtime={}", bot.getId(), bot.getRuntimeType());
            }
        }
    }

    public List<Bot> listOwned(Long userId) {
        return botRepository.findByOwnerUserIdOrderByIdAsc(userId);
    }

    public List<Bot> listAll() {
        return botRepository.findAll();
    }

    public Bot getOwned(Long id, Long userId, boolean admin) {
        Bot bot = botRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "机器人不存在: " + id));
        if (!admin && !bot.getOwnerUserId().equals(userId)) {
            throw new BizException(403, "无权访问该机器人");
        }
        return bot;
    }

    @Transactional
    public Bot create(Long ownerUserId, String name, String operator) {
        return create(ownerUserId, name, "LOCAL", null, null, operator);
    }

    @Transactional
    public Bot create(Long ownerUserId, String name, String runtimeType, String onebotUrl,
                      String webuiUrl, String operator) {
        if (!properties.isEnabled()) {
            throw new BizException(403, "多机器人功能未启用");
        }
        String cleanName = validateName(name);
        String rt = normalizeRuntime(runtimeType, onebotUrl, webuiUrl);
        long owned = botRepository.countByOwnerUserId(ownerUserId);
        if (owned >= properties.getMaxPerUser()) {
            throw new BizException(409, "每个用户最多创建 " + properties.getMaxPerUser() + " 个机器人");
        }
        int[] ports = allocatePorts();
        Path workdir = resolvePath(properties.getWorkdirRoot())
                .resolve("u" + ownerUserId + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(workdir);
        } catch (IOException e) {
            throw new BizException(500, "创建机器人工作目录失败: " + e.getMessage());
        }
        Bot bot = new Bot();
        bot.setOwnerUserId(ownerUserId);
        bot.setName(cleanName);
        bot.setStatus("PROVISIONING");
        bot.setRuntimeType(rt);
        bot.setOnebotUrl(blankToNull(onebotUrl));
        bot.setWebuiUrl(blankToNull(webuiUrl));
        bot.setAgentToken(newToken());
        bot.setNapcatPort(ports[0]);
        bot.setOnebotPort(ports[1]);
        bot.setOnebotToken(newToken());
        bot.setWorkdir(workdir.toAbsolutePath().normalize().toString());
        Bot saved = botRepository.save(bot);
        auditLogService.log(operator, "BOT_CREATE", String.valueOf(saved.getId()), "SUCCESS",
                "name=" + cleanName + " runtime=" + rt + " ports=" + ports[0] + "/" + ports[1]);
        log.info("创建机器人 id={} owner={} name={} ports={}/{}",
                saved.getId(), ownerUserId, cleanName, ports[0], ports[1]);
        return saved;
    }

    @Transactional
    public Bot updateRuntime(Long id, Long userId, boolean admin, String runtimeType,
                             String onebotUrl, String webuiUrl, String operator) {
        Bot bot = getOwned(id, userId, admin);
        String rt = normalizeRuntime(runtimeType, onebotUrl, webuiUrl);
        bot.setRuntimeType(rt);
        bot.setOnebotUrl("REMOTE".equals(rt) ? blankToNull(onebotUrl) : null);
        bot.setWebuiUrl("REMOTE".equals(rt) ? blankToNull(webuiUrl) : null);
        bot.setLastHeartbeatAt(null);
        if ("REMOTE".equals(rt)) {
            bot.setStatus("STOPPED");
        }
        Bot saved = botRepository.save(bot);
        auditLogService.log(operator, "BOT_RUNTIME_UPDATE", String.valueOf(id), "SUCCESS",
                "runtime=" + rt + " onebot=" + blankToNull(onebotUrl) + " webui=" + blankToNull(webuiUrl));
        return saved;
    }

    /** 远程 agent 心跳：用每 bot 独立 token 认证，不依赖登录态。 */
    @Transactional
    public Bot heartbeat(Long id, String token) {
        Bot bot = botRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "机器人不存在: " + id));
        if (token == null || bot.getAgentToken() == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                bot.getAgentToken().getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(403, "agent token 无效");
        }
        bot.setLastHeartbeatAt(LocalDateTime.now());
        bot.setStatus("RUNNING");
        bot.setLastError(null);
        return botRepository.save(bot);
    }

    @Transactional
    public String rotateAgentToken(Long id, Long userId, boolean admin, String operator) {
        Bot bot = getOwned(id, userId, admin);
        String token = newToken();
        bot.setAgentToken(token);
        botRepository.save(bot);
        auditLogService.log(operator, "BOT_AGENT_TOKEN_ROTATE", String.valueOf(id), "SUCCESS", "rotated");
        return token;
    }

    @Transactional
    public Bot rename(Long id, Long userId, boolean admin, String name, String operator) {
        Bot bot = getOwned(id, userId, admin);
        String cleanName = validateName(name);
        bot.setName(cleanName);
        Bot saved = botRepository.save(bot);
        auditLogService.log(operator, "BOT_RENAME", String.valueOf(id), "SUCCESS", "name=" + cleanName);
        return saved;
    }

    @Transactional
    public void delete(Long id, Long userId, boolean admin, String operator) {
        Bot bot = getOwned(id, userId, admin);
        if (!"PROVISIONING".equals(bot.getStatus()) && !"STOPPED".equals(bot.getStatus())) {
            throw new BizException(409, "请先停止机器人再删除（当前 " + bot.getStatus() + "）");
        }
        botRepository.delete(bot);
        auditLogService.log(operator, "BOT_DELETE", String.valueOf(id), "SUCCESS",
                "name=" + bot.getName() + " qq=" + bot.getQqNumber());
    }

    private int[] allocatePorts() {
        Set<Integer> used = new HashSet<>();
        for (Bot b : botRepository.findAll()) {
            if (b.getNapcatPort() != null) used.add(b.getNapcatPort());
            if (b.getOnebotPort() != null) used.add(b.getOnebotPort());
        }
        int candidate = properties.getPortRangeStart();
        for (int i = 0; i < 500; i++, candidate += 2) {
            if (!used.contains(candidate) && !used.contains(candidate + 1)) {
                return new int[]{candidate, candidate + 1};
            }
        }
        throw new BizException(409, "没有可用的机器人端口，请联系管理员");
    }

    private Long resolveDefaultOwner() {
        Long configured = properties.getDefaultOwner();
        if (configured != null && userRepository.existsById(configured)) {
            return configured;
        }
        return userRepository.findFirstByRoleAndActiveTrueOrderByIdAsc("ADMIN")
                .map(User::getId)
                .orElse(null);
    }

    private static String normalizeRuntime(String runtimeType, String onebotUrl, String webuiUrl) {
        String rt = (runtimeType == null || runtimeType.isBlank()) ? "LOCAL" : runtimeType.trim().toUpperCase();
        if (!"LOCAL".equals(rt) && !"REMOTE".equals(rt)) {
            throw new BizException(400, "runtimeType 只支持 LOCAL/REMOTE");
        }
        if ("REMOTE".equals(rt)) {
            requireHttpUrl(onebotUrl, "远程机器人必须填写 OneBot URL");
            if (webuiUrl != null && !webuiUrl.isBlank()) {
                requireHttpUrl(webuiUrl, "WebUI URL 必须是 http(s) 地址");
            }
        }
        return rt;
    }

    private static void requireHttpUrl(String url, String message) {
        if (url == null || url.isBlank()) {
            throw new BizException(400, message);
        }
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new BizException(400, message + "（需 http/https）");
        }
        if (url.length() > 255) {
            throw new BizException(400, "URL 最多 255 个字符");
        }
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(400, "机器人名称不能为空");
        }
        String clean = name.trim();
        if (clean.length() > 64) {
            throw new BizException(400, "机器人名称最多 64 个字符");
        }
        return clean;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Path resolvePath(String path) {
        Path p = Paths.get(path);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(p);
        }
        return p.toAbsolutePath().normalize();
    }
}
