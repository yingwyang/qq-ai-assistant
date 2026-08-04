package com.qqai.common;

import com.qqai.entity.UserQqBinding;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
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
        try {
            List<UserQqBinding> bindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
            if (bindings == null || bindings.isEmpty()) {
                return Collections.emptyList();
            }
            return bindings.stream()
                    .filter(b -> b != null && b.getQqNumber() != null && !b.getQqNumber().isBlank())
                    .map(UserQqBinding::getQqNumber)
                    .toList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
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

    public Long requireCurrentUserId() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new BizException(401, "UNAUTHORIZED", "未登录或登录已过期");
        }
        return userId;
    }

    public void requireAdmin() {
        String role = getCurrentUserRole();
        if (!"ADMIN".equals(role)) {
            throw new BizException(403, CreditErrorCode.ADMIN_REQUIRED, "需要管理员权限");
        }
    }

    public Long requireAdminUserId() {
        requireAdmin();
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new BizException(401, "UNAUTHORIZED", "未登录");
        }
        return userId;
    }

    public boolean isAdmin() {
        return "ADMIN".equals(getCurrentUserRole());
    }
}
