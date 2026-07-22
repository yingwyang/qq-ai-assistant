package com.qqai.dto.message;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DeleteByTypesRequest(
        @NotEmpty(message = "请选择要删除的消息类型")
        List<String> types,

        Boolean deleteMedia
) {
}
