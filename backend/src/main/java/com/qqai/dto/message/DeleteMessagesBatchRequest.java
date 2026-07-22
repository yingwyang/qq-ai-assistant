package com.qqai.dto.message;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DeleteMessagesBatchRequest(
        @NotEmpty(message = "请选择要删除的消息")
        List<Long> messageIds,

        Boolean deleteMedia
) {
}
