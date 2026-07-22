package com.qqai.dto.message;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DeleteMediaFilesRequest(
        @NotEmpty(message = "请选择要删除的文件")
        List<String> ids
) {
}
