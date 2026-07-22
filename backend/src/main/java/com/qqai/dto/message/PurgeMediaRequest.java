package com.qqai.dto.message;

import java.util.List;

public record PurgeMediaRequest(
        List<String> types
) {
}
