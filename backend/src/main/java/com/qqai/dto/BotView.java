package com.qqai.dto;

import com.qqai.entity.Bot;
import java.time.LocalDateTime;

/** 机器人对外视图（不包含 OneBot token 等敏感字段）。 */
public record BotView(
        Long id,
        Long ownerUserId,
        String name,
        String qqNumber,
        String status,
        String runtimeType,
        String onebotUrl,
        String webuiUrl,
        LocalDateTime lastHeartbeatAt,
        Integer napcatPort,
        Integer onebotPort,
        String workdir,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime startedAt) {

    public static BotView of(Bot b) {
        return new BotView(b.getId(), b.getOwnerUserId(), b.getName(), b.getQqNumber(), b.getStatus(),
                b.getRuntimeType(), b.getOnebotUrl(), b.getWebuiUrl(), b.getLastHeartbeatAt(),
                b.getNapcatPort(), b.getOnebotPort(), b.getWorkdir(), b.getLastError(),
                b.getCreatedAt(), b.getStartedAt());
    }
}
