package com.qqai.service;

import com.qqai.entity.Group;
import com.qqai.repository.GroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private MediaDownloadService mediaDownloadService;

    @Value("${file.storage.local-path:./uploads/images}")
    private String localImagePath;

    public Optional<Group> findByGroupIdAndOwnerQq(String groupId, String ownerQq) {
        return groupRepository.findByGroupIdAndOwnerQq(groupId, ownerQq);
    }

    public Group save(Group group) {
        return groupRepository.save(group);
    }

    public List<Group> findByOwnerQqAndActiveTrue(String ownerQq) {
        return groupRepository.findByOwnerQqAndActiveTrue(ownerQq);
    }

    public long count() {
        return groupRepository.count();
    }

    public List<Group> findByActiveTrue() {
        return groupRepository.findByActiveTrue();
    }

    /**
     * 保存群聊信息
     * 每个登录账号的群聊记录独立，保留最新
     */
    public void saveGroupInfo(String ownerQq, String groupId, String groupName) {
        try {
            Optional<Group> existingGroup = groupRepository.findByGroupIdAndOwnerQq(groupId, ownerQq);
            Group group;
            if (existingGroup.isPresent()) {
                group = existingGroup.get();
                if (groupName != null && !groupName.isEmpty() && !groupName.equals(group.getGroupName())) {
                    group.setGroupName(groupName);
                    log.info("更新群聊信息: {} -> {}", groupId, groupName);
                }
                boolean needDownloadAvatar = false;
                if (group.getAvatar() == null || group.getAvatar().isEmpty()) {
                    needDownloadAvatar = true;
                } else if (group.getAvatar().startsWith("/images/")) {
                    String avatarFileName = group.getAvatar().substring("/images/".length());
                    Path avatarPath = Paths.get(localImagePath, avatarFileName);
                    if (!Files.exists(avatarPath)) {
                        needDownloadAvatar = true;
                    }
                }

                if (needDownloadAvatar) {
                    String localAvatarPath = mediaDownloadService.downloadGroupAvatarToLocal(groupId);
                    if (localAvatarPath != null) {
                        group.setAvatar(localAvatarPath);
                        log.info("更新群聊头像: {} -> {}", groupId, localAvatarPath);
                    }
                }
                group.setJoinedTime(LocalDateTime.now());
                group.setActive(true);
                groupRepository.save(group);
            } else {
                group = new Group();
                group.setGroupId(groupId);
                group.setOwnerQq(ownerQq);
                group.setGroupName(groupName != null ? groupName : "群聊 " + groupId);
                String localAvatarPath = mediaDownloadService.downloadGroupAvatarToLocal(groupId);
                if (localAvatarPath != null) {
                    group.setAvatar(localAvatarPath);
                } else {
                    group.setAvatar("https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/100");
                }
                group.setActive(true);
                groupRepository.save(group);
                log.info("创建新群聊: 登录者={}, 群号={}, 群名={}", ownerQq, groupId, groupName);
            }
        } catch (Exception e) {
            log.error("保存群聊信息时出错: {}", e.getMessage(), e);
        }
    }
}
