package com.qqai.common;

import com.qqai.entity.UserQqBinding;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.security.AuthPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class SecurityHelper {

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    @Autowired
    private GroupRepository groupRepository;

    public Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof AuthPrincipal authPrincipal) {
                return authPrincipal.userId();
            }
        }
        return null;
    }

    public List<String> getCurrentUserQqBindings() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Collections.emptyList();
        }
        List<UserQqBinding> bindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
        return bindings.stream()
                .map(UserQqBinding::getQqNumber)
                .toList();
    }

    public boolean hasGroupAccess(String groupId, List<String> userQqList) {
        if (userQqList == null || userQqList.isEmpty()) {
            return false;
        }
        long count = groupRepository.countByGroupIdAndOwnerQqInAndActiveTrue(groupId, userQqList);
        return count > 0;
    }

    public boolean hasGroupAccess(String groupId) {
        List<String> userQqList = getCurrentUserQqBindings();
        return hasGroupAccess(groupId, userQqList);
    }

    public String getCurrentUserRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof AuthPrincipal authPrincipal) {
                return authPrincipal.role();
            }
        }
        return null;
    }

    /**
     * 获取当前登录用户名（用于审计日志）。无认证上下文时返回 "system"。
     */
    public String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof AuthPrincipal authPrincipal) {
                return authPrincipal.username();
            }
        }
        return "system";
    }
}
