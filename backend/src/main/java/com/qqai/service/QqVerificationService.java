package com.qqai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ绑定验证服务 - 通过向QQ发送验证码私信来验证QQ主人身份
 *
 * <p><b>两道频次限制</b>（此前只有第一道，于是登录用户可以把机器人当"给任意 QQ 发私信"的通道）：</p>
 * <ol>
 *   <li>每个 QQ 每天最多 {@value #MAX_DAILY_ATTEMPTS} 次；</li>
 *   <li>每个<b>账号</b>每天最多 {@value #MAX_DAILY_ATTEMPTS_PER_USER} 次（防群发）。</li>
 * </ol>
 *
 * <p><b>存储改有界缓存</b>：原先三个 {@code ConcurrentHashMap} 只靠"恰好读到空"来清理，
 * 键随 QQ 数无限增长（内存泄漏）。现在统一用 Caffeine 带容量上限 + 自动过期。</p>
 */
@Service
public class QqVerificationService {

    private static final Logger log = LoggerFactory.getLogger(QqVerificationService.class);

    /** 验证码有效期（分钟） */
    private static final long CODE_EXPIRE_MINUTES = 5;
    /** 验证码长度 */
    private static final int CODE_LENGTH = 6;
    /** 每个 QQ 每天最多发送验证码次数 */
    private static final int MAX_DAILY_ATTEMPTS = 10;
    /** 每个账号每天最多发送验证码次数（按账号维度收口，防止把机器人当私信群发器） */
    private static final int MAX_DAILY_ATTEMPTS_PER_USER = 10;
    /** 同一验证码最多尝试次数，超过即作废 */
    private static final int MAX_VERIFY_ATTEMPTS = 10;

    private static final Duration CODE_TTL = Duration.ofMinutes(CODE_EXPIRE_MINUTES);

    @Autowired
    private com.qqai.capability.im.ImGateway imGateway;

    private final SecureRandom random = new SecureRandom();

    /** 验证码：key = qqNumber */
    private final Cache<String, VerificationCode> codeStore = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(CODE_TTL)
            .build();

    /** 每日发送次数：key = qqNumber_yyyy-MM-dd 或 user_<userId>_yyyy-MM-dd */
    private final Cache<String, AtomicInteger> dailyAttemptStore = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofDays(1))
            .build();

    /** 验证码爆破计数：key = qqNumber，与验证码同寿命即可 */
    private final Cache<String, AtomicInteger> verifyAttempts = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(CODE_TTL)
            .build();

    /** 发送结果（区分"被限流"与"发送失败"，便于前端给出准确提示） */
    public enum SendResult {
        SENT,
        RATE_LIMITED,
        SEND_FAILED
    }

    /**
     * 发送验证码到指定QQ号。
     *
     * @param qqNumber 目标QQ号
     * @param userId   发起请求的系统账号（用于账号级每日限额；为 null 时跳过账号级校验）
     * @return 发送结果
     */
    public SendResult sendVerificationCode(String qqNumber, Long userId) {
        String today = LocalDate.now().toString();

        // 1) 账号级每日限额：防止一个账号给大量 QQ 发私信（机器人被当群发器）
        if (userId != null && !incrementWithinLimit("user:" + userId + "_" + today, MAX_DAILY_ATTEMPTS_PER_USER)) {
            log.warn("账号 {} 今日验证码发送次数已达上限({})，拒绝", userId, MAX_DAILY_ATTEMPTS_PER_USER);
            return SendResult.RATE_LIMITED;
        }

        // 2) 单个 QQ 每日限额
        if (!incrementWithinLimit(qqNumber + "_" + today, MAX_DAILY_ATTEMPTS)) {
            log.warn("QQ {} 今日验证码发送次数已达上限({})，拒绝", qqNumber, MAX_DAILY_ATTEMPTS);
            return SendResult.RATE_LIMITED;
        }

        // 生成验证码并通过 NapCat 发私信
        String code = generateCode();
        String message = String.format(
            "【铃音QQ助手】您正在进行QQ账号绑定验证，验证码为：%s\n" +
            "该验证码 %d 分钟内有效，请勿泄露给他人。\n" +
            "如非本人操作，请忽略此消息。",
            code, CODE_EXPIRE_MINUTES
        );

        boolean sent = imGateway.sendPrivateMessage(qqNumber, message);
        if (!sent) {
            log.error("发送验证码到QQ {} 失败", qqNumber);
            return SendResult.SEND_FAILED;
        }

        codeStore.put(qqNumber, new VerificationCode(code));
        verifyAttempts.invalidate(qqNumber);
        log.info("验证码已发送到QQ {}（发起账号 {}）", qqNumber, userId);
        return SendResult.SENT;
    }

    /** 计数 +1 并在达到上限前放行；原子累加，并发下也不会多放行 */
    private boolean incrementWithinLimit(String key, int maxRequests) {
        AtomicInteger counter = dailyAttemptStore.get(key, k -> new AtomicInteger());
        return counter.incrementAndGet() <= maxRequests;
    }

    /**
     * 验证验证码
     *
     * @param qqNumber QQ号
     * @param code 用户输入的验证码
     * @return 验证结果
     */
    public VerificationResult verifyCode(String qqNumber, String code) {
        VerificationCode stored = codeStore.getIfPresent(qqNumber);
        if (stored == null) {
            return VerificationResult.NO_CODE;
        }

        // 双保险：缓存已有 TTL，这里再按时间判定一次
        if (isExpired(stored)) {
            codeStore.invalidate(qqNumber);
            return VerificationResult.EXPIRED;
        }

        if (!stored.code.equalsIgnoreCase(code)) {
            // 爆破防护：累计同一验证码的失败次数
            AtomicInteger attempts = verifyAttempts.get(qqNumber, k -> new AtomicInteger());
            if (attempts.incrementAndGet() >= MAX_VERIFY_ATTEMPTS) {
                codeStore.invalidate(qqNumber);
                verifyAttempts.invalidate(qqNumber);
                log.warn("QQ {} 验证码爆破防护触发，累计失败{}次，验证码已作废", qqNumber, attempts.get());
                return VerificationResult.EXPIRED;
            }
            return VerificationResult.INVALID;
        }

        // 验证成功，清除验证码和爆破计数
        codeStore.invalidate(qqNumber);
        verifyAttempts.invalidate(qqNumber);
        return VerificationResult.SUCCESS;
    }

    /**
     * 检查是否存在未过期的验证码
     *
     * @param qqNumber QQ号
     */
    public boolean hasValidCode(String qqNumber) {
        VerificationCode stored = codeStore.getIfPresent(qqNumber);
        if (stored == null) {
            return false;
        }
        if (isExpired(stored)) {
            codeStore.invalidate(qqNumber);
            return false;
        }
        return true;
    }

    private boolean isExpired(VerificationCode stored) {
        return Duration.between(stored.timestamp, Instant.now()).toMinutes() > CODE_EXPIRE_MINUTES;
    }

    /** 生成随机数字验证码 */
    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * 兼容旧调用点：缓存已自带过期与容量上限，无需手动清理。
     */
    @Deprecated
    public void cleanupExpiredCodes() {
        codeStore.cleanUp();
        verifyAttempts.cleanUp();
        dailyAttemptStore.cleanUp();
    }

    /** 验证码内部类 */
    private static class VerificationCode {
        final String code;
        final Instant timestamp;

        VerificationCode(String code) {
            this.code = code;
            this.timestamp = Instant.now();
        }
    }

    /**
     * 验证结果枚举
     */
    public enum VerificationResult {
        SUCCESS,    // 验证成功
        NO_CODE,    // 未找到验证码
        EXPIRED,    // 验证码已过期
        INVALID     // 验证码错误
    }
}
