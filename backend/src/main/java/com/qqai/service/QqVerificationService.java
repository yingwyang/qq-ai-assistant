package com.qqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QQ绑定验证服务 - 通过向QQ发送验证码私信来验证QQ主人身份
 */
@Service
public class QqVerificationService {

    private static final Logger log = LoggerFactory.getLogger(QqVerificationService.class);

    // 验证码有效期（分钟）
    private static final long CODE_EXPIRE_MINUTES = 5;
    // 验证码长度
    private static final int CODE_LENGTH = 6;
    // 每个QQ每天最多发送验证码次数
    private static final int MAX_DAILY_ATTEMPTS = 10;

    @Autowired
    private NapCatService napCatService;

    private final SecureRandom random = new SecureRandom();

    // 内存存储验证码: key = qqNumber, value = VerificationCode
    private final Map<String, VerificationCode> codeStore = new ConcurrentHashMap<>();
    // 每日发送次数限制: key = qqNumber + "_" + date, value = count
    private final Map<String, Integer> dailyAttemptStore = new ConcurrentHashMap<>();

    /**
     * 发送验证码到指定QQ号
     * @param qqNumber 目标QQ号
     * @return 是否发送成功
     */
    public boolean sendVerificationCode(String qqNumber) {
        // 检查每日发送次数限制
        String todayKey = qqNumber + "_" + java.time.LocalDate.now();
        int attempts = dailyAttemptStore.getOrDefault(todayKey, 0);
        if (attempts >= MAX_DAILY_ATTEMPTS) {
            log.warn("QQ {} 今日验证码发送次数已达上限", qqNumber);
            return false;
        }

        // 生成验证码
        String code = generateCode();

        // 构建验证消息
        String message = String.format(
            "【铃音QQ助手】您正在进行QQ账号绑定验证，验证码为：%s\n" +
            "该验证码 %d 分钟内有效，请勿泄露给他人。\n" +
            "如非本人操作，请忽略此消息。",
            code, CODE_EXPIRE_MINUTES
        );

        // 通过NapCat发送私信
        boolean sent = napCatService.sendPrivateMessage(qqNumber, message);
        if (sent) {
            // 存储验证码
            codeStore.put(qqNumber, new VerificationCode(code, Instant.now()));
            // 更新发送次数
            dailyAttemptStore.put(todayKey, attempts + 1);
            log.info("验证码已发送到QQ {}", qqNumber);
        } else {
            log.error("发送验证码到QQ {} 失败", qqNumber);
        }

        return sent;
    }

    /**
     * 验证验证码
     * @param qqNumber QQ号
     * @param code 用户输入的验证码
     * @return 验证结果
     */
    public VerificationResult verifyCode(String qqNumber, String code) {
        VerificationCode stored = codeStore.get(qqNumber);
        if (stored == null) {
            return VerificationResult.NO_CODE;
        }

        // 检查是否过期
        long elapsedMinutes = java.time.Duration.between(stored.timestamp, Instant.now()).toMinutes();
        if (elapsedMinutes > CODE_EXPIRE_MINUTES) {
            codeStore.remove(qqNumber);
            return VerificationResult.EXPIRED;
        }

        // 验证验证码
        if (!stored.code.equalsIgnoreCase(code)) {
            return VerificationResult.INVALID;
        }

        // 验证成功，清除验证码
        codeStore.remove(qqNumber);
        return VerificationResult.SUCCESS;
    }

    /**
     * 检查是否存在未过期的验证码
     * @param qqNumber QQ号
     * @return 是否存在有效验证码
     */
    public boolean hasValidCode(String qqNumber) {
        VerificationCode stored = codeStore.get(qqNumber);
        if (stored == null) {
            return false;
        }
        long elapsedMinutes = java.time.Duration.between(stored.timestamp, Instant.now()).toMinutes();
        if (elapsedMinutes > CODE_EXPIRE_MINUTES) {
            codeStore.remove(qqNumber);
            return false;
        }
        return true;
    }

    /**
     * 生成随机数字验证码
     */
    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * 清理过期的验证码（可由定时任务调用）
     */
    public void cleanupExpiredCodes() {
        Instant now = Instant.now();
        codeStore.entrySet().removeIf(entry -> {
            long elapsedMinutes = java.time.Duration.between(entry.getValue().timestamp, now).toMinutes();
            return elapsedMinutes > CODE_EXPIRE_MINUTES;
        });
    }

    /**
     * 验证码内部类
     */
    private static class VerificationCode {
        final String code;
        final Instant timestamp;

        VerificationCode(String code, Instant timestamp) {
            this.code = code;
            this.timestamp = timestamp;
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
