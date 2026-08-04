package com.qqai.service;

import com.qqai.repository.SubscriptionOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class OrderNoGenerator {

    private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int RANDOM_LENGTH = 6;
    private static final int MAX_RETRIES = 10;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private SubscriptionOrderRepository subscriptionOrderRepository;

    /**
     * 生成订单号：格式 SUB-YYYYMMDD-6位base36 随机串。
     * 线程安全：SecureRandom 本身线程安全；订单号唯一性通过 existsByOrderNo 数据库查询保证。
     * 碰撞重试：若生成号已存在则重试，最多 MAX_RETRIES(10) 次，超出抛 IllegalStateException。
     * 设计理由：日期前缀便于按天分桶与人工排查，base36 随机段保证单日内碰撞概率极低。
     */
    public String generate() {
        String datePart = LocalDate.now().format(DATE_FORMAT);
        int retries = 0;
        while (retries < MAX_RETRIES) {
            String orderNo = "SUB-" + datePart + "-" + generateRandomPart();
            if (!subscriptionOrderRepository.existsByOrderNo(orderNo)) {
                return orderNo;
            }
            retries++;
        }
        throw new IllegalStateException("订单号生成失败，已重试" + MAX_RETRIES + "次");
    }

    String generateRandomPart() {
        StringBuilder sb = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(BASE36.charAt(random.nextInt(BASE36.length())));
        }
        return sb.toString();
    }
}
