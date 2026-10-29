package com.qqai.service;

import com.qqai.config.BotProperties;
import com.qqai.entity.Bot;
import com.qqai.entity.User;
import com.qqai.exception.BizException;
import com.qqai.repository.BotRepository;
import com.qqai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/** P1：机器人归属/端口分配/上限/默认种子。 */
class BotServiceTest {

    @TempDir
    Path tempDir;

    private BotRepository botRepository;
    private UserRepository userRepository;
    private AuditLogService auditLogService;
    private BotProperties properties;
    private BotService service;

    @BeforeEach
    void setUp() {
        botRepository = mock(BotRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);
        properties = new BotProperties();
        properties.setWorkdirRoot(tempDir.toString());
        properties.setDefaultWorkdir(tempDir.resolve("default-napcat").toString());
        properties.setMaxPerUser(1);
        service = new BotService(botRepository, userRepository, properties, auditLogService);
        given(botRepository.save(any(Bot.class))).willAnswer(inv -> {
            Bot bot = inv.getArgument(0);
            if (bot.getId() == null) bot.setId(9L);
            return bot;
        });
    }

    @Test
    @DisplayName("创建机器人：分配 16100/16101、独立 token、工作目录、审计")
    void createAllocatesResources() {
        given(botRepository.countByOwnerUserId(2L)).willReturn(0L);
        given(botRepository.findAll()).willReturn(List.of());

        Bot bot = service.create(2L, "我的机器人", "alice");

        assertEquals(2L, bot.getOwnerUserId());
        assertEquals("我的机器人", bot.getName());
        assertEquals(16100, bot.getNapcatPort());
        assertEquals(16101, bot.getOnebotPort());
        assertEquals(32, bot.getOnebotToken().length());
        assertEquals("PROVISIONING", bot.getStatus());
        assertTrue(Path.of(bot.getWorkdir()).startsWith(tempDir));
        verify(auditLogService).log("alice", "BOT_CREATE", "9", "SUCCESS",
                "name=我的机器人 runtime=LOCAL ports=16100/16101");
    }

    @Test
    @DisplayName("远程机器人：必须有 OneBot URL，LOCAL 不需要")
    void remoteRuntimeRequiresUrl() {
        assertThrows(BizException.class, () -> service.create(2L, "远程", "REMOTE", null, null, "alice"));
        Bot bot = service.create(2L, "远程", "REMOTE", "http://10.0.0.5:6100", "http://10.0.0.5:6099", "alice");
        assertEquals("REMOTE", bot.getRuntimeType());
        assertEquals("http://10.0.0.5:6100", bot.getOnebotUrl());
    }

    @Test
    @DisplayName("心跳：正确 token → RUNNING + lastHeartbeat；错误 token → 403")
    void heartbeatValidatesToken() {
        Bot bot = new Bot();
        bot.setId(7L);
        bot.setOwnerUserId(2L);
        bot.setAgentToken("agent-token-123");
        bot.setStatus("STOPPED");
        given(botRepository.findById(7L)).willReturn(Optional.of(bot));

        assertThrows(BizException.class, () -> service.heartbeat(7L, "wrong"));
        Bot saved = service.heartbeat(7L, "agent-token-123");
        assertEquals("RUNNING", saved.getStatus());
        assertNotNull(saved.getLastHeartbeatAt());
    }

    @Test
    @DisplayName("轮换 agent token：旧 token 失效")
    void rotatesAgentToken() {
        Bot bot = new Bot();
        bot.setId(7L);
        bot.setOwnerUserId(2L);
        bot.setAgentToken("old-token");
        given(botRepository.findById(7L)).willReturn(Optional.of(bot));

        String rotated = service.rotateAgentToken(7L, 2L, false, "alice");

        assertNotEquals("old-token", rotated);
        assertEquals(rotated, bot.getAgentToken());
    }

    @Test
    @DisplayName("每用户上限：已有 1 个再创建 → 409")
    void rejectsOverLimit() {
        given(botRepository.countByOwnerUserId(2L)).willReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> service.create(2L, "第二个", "alice"));

        assertEquals(409, ex.getCode());
        verify(botRepository, never()).save(any());
    }

    @Test
    @DisplayName("端口分配跳过已占用：已有 16100/16101 → 新 bot 用 16102/16103")
    void skipsUsedPorts() {
        given(botRepository.countByOwnerUserId(2L)).willReturn(0L);
        Bot used = new Bot();
        used.setNapcatPort(16100);
        used.setOnebotPort(16101);
        given(botRepository.findAll()).willReturn(List.of(used));

        Bot bot = service.create(2L, "第二个", "alice");

        assertEquals(16102, bot.getNapcatPort());
        assertEquals(16103, bot.getOnebotPort());
    }

    @Test
    @DisplayName("归属校验：非 owner 访问 → 403，管理员可访问")
    void enforcesOwnership() {
        Bot bot = new Bot();
        bot.setId(5L);
        bot.setOwnerUserId(2L);
        given(botRepository.findById(5L)).willReturn(Optional.of(bot));

        BizException ex = assertThrows(BizException.class, () -> service.getOwned(5L, 3L, false));
        assertEquals(403, ex.getCode());
        assertNotNull(service.getOwned(5L, 3L, true));
    }

    @Test
    @DisplayName("删除：运行中 409；STOPPED 才允许")
    void deleteOnlyStopped() {
        Bot running = new Bot();
        running.setId(5L);
        running.setOwnerUserId(2L);
        running.setStatus("RUNNING");
        given(botRepository.findById(5L)).willReturn(Optional.of(running));
        assertEquals(409, assertThrows(BizException.class,
                () -> service.delete(5L, 2L, false, "alice")).getCode());

        running.setStatus("STOPPED");
        service.delete(5L, 2L, false, "alice");
        verify(botRepository).delete(running);
    }

    @Test
    @DisplayName("默认种子：没有 bots 时用最早 ADMIN 建默认机器人")
    void seedsDefaultBot() {
        given(botRepository.count()).willReturn(0L);
        User admin = new User();
        admin.setId(2L);
        admin.setRole("ADMIN");
        given(userRepository.findFirstByRoleAndActiveTrueOrderByIdAsc("ADMIN"))
                .willReturn(Optional.of(admin));

        service.seedDefaultBot();

        verify(botRepository).save(any(Bot.class));
        verify(auditLogService).log(eq("SYSTEM"), eq("BOT_SEED_DEFAULT"), anyString(), eq("SUCCESS"), anyString());
    }

    @Test
    @DisplayName("默认种子关闭时不建；已有 bot 时幂等")
    void seedIsIdempotentAndSwitchable() {
        properties.setSeedDefault(false);
        service.seedDefaultBot();
        verify(botRepository, never()).save(any());

        properties.setSeedDefault(true);
        given(botRepository.count()).willReturn(1L);
        service.seedDefaultBot();
        verify(botRepository, never()).save(any());
    }
}
