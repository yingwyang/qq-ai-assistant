package com.qqai.config;

import com.qqai.common.SecurityHelper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 媒体访问控制过滤器（/images/**、/uploads/** 的对象级授权）。
 *
 * <p>背景：这两个前缀此前是 {@code permitAll}，任何匿名请求只要拿到 URL 就能读取群聊图片/语音/视频；
 * 而 {@code MessageController#downloadFile} 里做的「绑定 QQ 是否拥有该群」校验被静态资源映射直接绕过，
 * 等于没有鉴权。</p>
 *
 * <p>执行位置：由 SecurityConfig 挂在 {@link com.qqai.security.JwtAuthenticationFilter} 之后，
 * 此时 SecurityContext 已建立，可读取当前用户与角色。</p>
 *
 * <p>授权矩阵：</p>
 * <ol>
 *   <li>{@code /uploads/avatars/**} → 公开（群头像/用户头像展示场景，保持匿名可用）；</li>
 *   <li>匿名 → 401；</li>
 *   <li>ADMIN → 放行；</li>
 *   <li>{@code /images/<mediaType>/<groupId>/...} → 必须拥有该群（{@code hasGroupAccess}）；</li>
 *   <li>{@code /uploads/tts/<ownerId>/...} → 仅该 userId 本人；</li>
 *   <li>其余 {@code /uploads/**}（temp/archive/chat-tmp/outbound 等服务端中间产物）→ 仅 ADMIN；</li>
 *   <li>其余 {@code /images/**}（旧版平铺头像等无法归属到群的文件）→ 登录即可。</li>
 * </ol>
 */
@Component
public class MediaAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MediaAccessFilter.class);

    private static final String IMAGES_PREFIX = "/images/";
    private static final String UPLOADS_PREFIX = "/uploads/";
    private static final String AVATARS_PREFIX = "/uploads/avatars/";
    private static final String TTS_PREFIX = "/uploads/tts/";

    /** 群号形态：QQ 群号至少 5 位数字 */
    private static final Pattern GROUP_ID = Pattern.compile("\\d{5,}");

    /** 媒体类型目录名（与 MediaDownloadService 的落盘目录一致） */
    private static final Set<String> MEDIA_TYPE_DIRS =
            Set.of("image", "images", "video", "voice", "audio", "file", "files", "others");

    @Autowired
    private SecurityHelper securityHelper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = resolvePath(request);
        return !path.startsWith(IMAGES_PREFIX) && !path.startsWith(UPLOADS_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = resolvePath(request);

        // 路径穿越防护：ACL 基于原始 URI 解析群号/归属，若放行 ".." 与编码点号，可构造
        // /images/images/<自己拥有的群号>/../../uploads/tts/<他人id>/x.wav 绕过归属校验。
        if (isSuspiciousPath(path)) {
            log.warn("【安全】拒绝可疑媒体路径: {}", path);
            reject(response, 400, "非法路径");
            return;
        }

        // 1. 头像保持公开
        if (path.startsWith(AVATARS_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 必须已登录
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            reject(response, 401, "未登录或登录已过期");
            return;
        }

        // 3. 管理员放行全部媒体
        if (securityHelper.isAdmin()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. 群聊媒体：按「绑定 QQ 是否拥有该群」校验
        if (path.startsWith(IMAGES_PREFIX)) {
            String groupId = extractGroupId(path);
            if (groupId == null) {
                // 旧版平铺文件（如 /images/avatars/...）无法归属到具体群，退化为「登录即可」
                filterChain.doFilter(request, response);
                return;
            }
            if (!securityHelper.hasGroupAccess(groupId)) {
                log.warn("【安全】拒绝访问媒体: userId={} 无权访问群 {} 的文件 {}", userId, groupId, path);
                reject(response, 403, "无权访问该群聊的媒体文件");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 5. TTS 语音：仅生成者本人
        if (path.startsWith(TTS_PREFIX)) {
            String ownerId = segmentAt(path, 3);
            if (ownerId == null || !ownerId.equals(String.valueOf(userId))) {
                log.warn("【安全】拒绝访问 TTS 语音: userId={} 非文件归属者 {} ({})", userId, ownerId, path);
                reject(response, 403, "无权访问该语音文件");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 6. 其余 /uploads/** 为服务端中间产物（temp/archive/chat-tmp/outbound 等），仅管理员可见
        log.warn("【安全】拒绝普通用户访问上传目录非公开子路径: userId={} path={}", userId, path);
        reject(response, 403, "无权访问该文件");
    }

    /** 去掉 contextPath 后的请求路径 */
    private String resolvePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        return path;
    }

    /** 路径穿越 / 重复分隔符等可疑形态（fail-closed） */
    private boolean isSuspiciousPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("..") || lower.contains("%2e") || lower.contains("\\") || lower.contains("//");
    }

    /**
     * 从规范形态 {@code /images/<mediaType>/<groupId>/<date>/<file>} 中解析群号。
     * 形态不符（旧版平铺文件）返回 null。
     */
    private String extractGroupId(String path) {
        String mediaType = segmentAt(path, 2);
        if (mediaType == null || !MEDIA_TYPE_DIRS.contains(mediaType.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String groupId = segmentAt(path, 3);
        if (groupId == null || !GROUP_ID.matcher(groupId).matches()) {
            return null;
        }
        return groupId;
    }

    /** 取以 "/" 分隔的第 index 段（index 0 为前导斜杠前的空串） */
    private String segmentAt(String path, int index) {
        String[] parts = path.split("/");
        return index >= 0 && index < parts.length ? parts[index] : null;
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":" + status + "}");
    }
}
