package com.qqai.dto.message;

public record RecentGroupDto(
        String groupId,
        String groupName,
        String lastMessage,
        Long lastMessageTime,
        Integer unreadCount,
        String selfQq,
        Boolean isDefault,
        String avatar,
        String ownerQq
) {
}
