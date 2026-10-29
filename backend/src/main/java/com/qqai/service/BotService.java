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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        if (!properties.isEnabled() || !properties.isSeedDefault() || botRepository.count() > 0) {
            return;
        }
        Long ownerId = resolveDefaultOwner();
        if (ownerId == null) {
            log.warn("跳过默认机器人种子：没有可用的 ADMIN 用户");
            return;
        }
        Bot bot = new Bot();
        bot.setOwnerUserId(ownerId);
        bot.setName(properties.getDefaultName());
        bot.setQqNumber(blankToNull(properties.getDefaultQq()));
        bot.setStatus("STOPPED");
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
        if (!properties.isEnabled()) {
            throw new BizException(403, "多机器人功能未启用");
        }
        String cleanName = validateName(name);
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
        bot.setNapcatPort(ports[0]);
        bot.setOnebotPort(ports[1]);
        bot.setOnebotToken(newToken());
        bot.setWorkdir(workdir.toAbsolutePath().normalize().toString());
        Bot saved = botRepository.save(bot);
        auditLogService.log(operator, "BOT_CREATE", String.valueOf(saved.getId()), "SUCCESS",
                "name=" + cleanName + " ports=" + ports[0] + "/" + ports[1]);
        log.info("创建机器人 id={} owner={} name={} ports={}/{}",
                saved.getId(), ownerUserId, cleanName, ports[0], ports[1]);
        return saved;
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
