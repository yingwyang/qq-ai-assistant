package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.constant.GroupType;
import com.qqai.dto.common.ApiResponse;
import com.qqai.dto.webhook.GroupDigestPayload;
import com.qqai.entity.Group;
import com.qqai.entity.GroupDigest;
import com.qqai.exception.BizException;
import com.qqai.repository.GroupRepository;
import com.qqai.service.AuditLogService;
import com.qqai.service.CreditService;
import com.qqai.service.GroupDigestService;
import com.qqai.service.GroupTypeRecognitionService;
import com.qqai.service.MessageQueueService;
import com.qqai.service.NapCatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 群类型管理 + 群日报 Controller
 * 提供群类型查询、手动设置、AI 自动识别接口；以及 AI 摘要阶段 3 的「群日报」接口
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private static final Logger log = LoggerFactory.getLogger(GroupController.class);

    /** 网页发送群消息的长度上限（字符） */
    private static final int MAX_GROUP_SEND_LENGTH = 2000;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupTypeRecognitionService recognitionService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private GroupDigestService groupDigestService;

    @Autowired
    private MessageQueueService messageQueueService;

    /** 手动推送日报到 QQ 群（仅管理员）用的 NapCat 发送能力 */
    @Autowired
    private NapCatService napCatService;

    /** 手动推送日报的审计日志 */
    @Autowired
    private AuditLogService auditLogService;

    /** 网页发送群消息的限流（每用户 10 次/分钟） */
    @Autowired
    private com.qqai.common.RateLimiterService rateLimiterService;

    /**
     * 查询群类型 + 推荐分析类型
     */
    @GetMapping("/{groupId}/type")
    public ResponseEntity<?> getGroupType(@PathVariable String groupId) {
        try {
            List<Group> groups = groupRepository.safeFindByGroupId(groupId);
            if (groups.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "error", "message", "群聊不存在"));
            }
            Group group = groups.get(0);
            GroupType gt = GroupType.fromString(group.getGroupType());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("groupId", groupId);
            result.put("groupType", gt.name());
            result.put("groupTypeLabel", gt.getLabel());
            result.put("groupTypeIcon", gt.getIcon());
            result.put("recommendedAnalysisTypes", gt.getRecommendedAnalysisTypes());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询群类型失败 groupId={}: {}", groupId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 手动设置群类型
     */
    @PutMapping("/{groupId}/type")
    public ResponseEntity<?> setGroupType(@PathVariable String groupId,
                                          @RequestBody Map<String, String> request) {
        try {
            String groupType = request.get("groupType");
            if (!GroupType.isValid(groupType) && !"OTHER".equalsIgnoreCase(groupType)) {
                return ResponseEntity.ok(Map.of("status", "error", "message", "无效的群类型: " + groupType));
            }

            List<Group> groups = groupRepository.safeFindByGroupId(groupId);
            if (groups.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "error", "message", "群聊不存在"));
            }

            GroupType gt = GroupType.fromString(groupType);
            for (Group group : groups) {
                group.setGroupType(gt.name());
                groupRepository.save(group);
            }

            log.info("群类型已设置 groupId={} -> {}", groupId, gt.name());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("groupType", gt.name());
            result.put("groupTypeLabel", gt.getLabel());
            result.put("groupTypeIcon", gt.getIcon());
            result.put("recommendedAnalysisTypes", gt.getRecommendedAnalysisTypes());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("设置群类型失败 groupId={}: {}", groupId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * AI 自动识别群类型（消耗积分，识别结果不自动写入，需用户确认）
     */
    @PostMapping("/{groupId}/recognize-type")
    public ResponseEntity<?> recognizeGroupType(@PathVariable String groupId,
                                                @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            Long userId = securityHelper.getCurrentUserId();
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("status", "error", "message", "未登录"));
            }

            boolean isAdmin = securityHelper.isAdmin();

            // 只做余额校验（不扣费）；真正扣费挪到识别成功之后。
            // 此前是"先扣 1 积分再识别"，LLM 一失败用户就白扣一次。
            if (!isAdmin) {
                int balance = creditService.getBalance(userId).map(b -> b.getBalance()).orElse(0);
                if (balance < 1) {
                    return ResponseEntity.status(402).body(Map.of(
                            "status", "error",
                            "errorCode", "INSUFFICIENT_CREDITS",
                            "message", "积分不足，无法执行群类型识别"));
                }
            }

            GroupTypeRecognitionService.RecognitionResult result =
                    recognitionService.recognizeGroupType(groupId, forceRefresh);

            if (!result.success) {
                // 识别失败不扣积分，失败原因原样返回（前端会弹 error toast）
                log.warn("群类型识别未成功 groupId={}: {}", groupId, result.reason);
                return ResponseEntity.ok(Map.of(
                        "status", "error",
                        "errorCode", "RECOGNITION_FAILED",
                        "message", "AI 识别失败：" + result.reason));
            }

            int groupCost = 0;
            try {
                CreditService.CreditCostResult groupResult = creditService.spendForGroupType(userId, groupId, isAdmin);
                groupCost = groupResult.getCost();
            } catch (com.qqai.exception.BizException e) {
                log.warn("群类型识别扣费失败(结果已正常返回) userId={}, groupId={}: {}", userId, groupId, e.getMessage());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.putAll(result.toMap());
            response.put("cost", groupCost);
            response.put("message", "识别完成，请确认是否采用此类型");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("群类型识别失败 groupId={}: {}", groupId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ==================== AI 摘要阶段 3：群日报 ====================

    /**
     * 生成（或读取）某群当天日报：{@code POST /api/groups/{groupId}/digest?date=YYYY-MM-DD}
     *
     * <p>{@code date} 可空，为空表示今天。幂等：同群同天已有日报时直接返回已有记录，不再调用大模型。</p>
     *
     * <p>失败语义：参数/无消息等业务异常由 {@code GroupDigestService} 抛 {@code BizException}，
     * 经 {@code GlobalExceptionHandler} 统一转成 {@code ApiResponse}（400 该群当天没有可摘要的消息 /
     * 503 AI 服务不可用）。</p>
     */
    @PostMapping("/{groupId}/digest")
    public ResponseEntity<ApiResponse<?>> generateGroupDigest(
            @PathVariable String groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean force) {

        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }

        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        GroupDigest digest = groupDigestService.generateDailyDigest(groupId, targetDate, force);
        return ResponseEntity.ok(ApiResponse.success(groupDigestService.toView(digest)));
    }

    /**
     * 异步生成群日报：{@code POST /api/groups/{groupId}/digest/async?date=YYYY-MM-DD&force=false}
     *
     * <p>把 {@code {groupId, date, force}} 投递到独立队列 {@code group.digest.queue} 后
     * <b>立即返回</b>（前端拿到 {@code data = {status:"queued", groupId, date, force}}），
     * 生成结果由消费者写库，前端再通过 {@code /digest/latest} 轮询查看。</p>
     *
     * <p>与同步接口的分工：同步接口（{@code POST /digest}）保持原样、等大模型返回后给出结果，
     * 现有链路不受影响；本接口用于「不想等」或定时任务这类场景。</p>
     *
     * <p>权限：与其余 digest 接口同一套 {@link #checkGroupAccess(String)}
     * （未登录 401 / 未绑定 QQ 403 / 无群可见性 403），并额外过
     * {@link GroupDigestService#assertDigestAllowed(String)}（{@code enabled=false} 或不在群白名单 → 403）。</p>
     */
    @PostMapping("/{groupId}/digest/async")
    public ResponseEntity<ApiResponse<?>> generateGroupDigestAsync(
            @PathVariable String groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean force) {

        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }

        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        // 开关 / 群白名单：不满足直接 403（BizException 由 GlobalExceptionHandler 统一转 ApiResponse）
        groupDigestService.assertDigestAllowed(groupId);

        messageQueueService.sendGroupDigest(new GroupDigestPayload(groupId, targetDate.toString(), force));
        log.info("群日报任务已投递到 group.digest.queue groupId={}, date={}, force={}", groupId, targetDate, force);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "queued");
        data.put("groupId", groupId);
        data.put("date", targetDate.toString());
        data.put("force", force);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 最新一期日报：{@code GET /api/groups/{groupId}/digest/latest}
     *
     * <p>该群还没有任何日报时 {@code data} 为 null（不是错误），前端据此显示「今日暂无速览」。</p>
     */
    @GetMapping("/{groupId}/digest/latest")
    public ResponseEntity<ApiResponse<?>> getLatestGroupDigest(@PathVariable String groupId) {
        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }
        // 白名单外的群连日报也不允许读取（enabled=false 时同理 → 403）
        groupDigestService.assertDigestAllowed(groupId);

        Optional<GroupDigest> latest = groupDigestService.findLatest(groupId);
        return ResponseEntity.ok(ApiResponse.success(latest.map(groupDigestService::toView).orElse(null)));
    }

    /**
     * 历史日报列表：{@code GET /api/groups/{groupId}/digests?limit=30}
     *
     * <p>{@code limit} 默认 30，服务层收敛到 1 ~ 200。</p>
     */
    @GetMapping("/{groupId}/digests")
    public ResponseEntity<ApiResponse<?>> getGroupDigests(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "30") int limit) {

        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }
        groupDigestService.assertDigestAllowed(groupId);

        List<Map<String, Object>> views = groupDigestService.findHistory(groupId, limit)
                .stream()
                .map(groupDigestService::toView)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(views));
    }

    /**
     * 手动把某天的日报推送到该 QQ 群：{@code POST /api/groups/{groupId}/digest/push?date=YYYY-MM-DD}
     *
     * <p><b>仅管理员</b>（{@code @PreAuthorize("hasRole('ADMIN')")}），并且与其余日报接口一样过
     * {@link GroupDigestService#assertDigestAllowed(String)}（{@code enabled=false} 或群不在白名单 → 403）。</p>
     *
     * <p><b>只推送、不生成</b>：{@code date} 为空表示今天；当天没有已存在的日报时直接 400
     * 「该群当天还没有速览，请先生成」，绝不隐式调用大模型。</p>
     *
     * <p>推送文本由 {@link GroupDigestService#buildPushText(GroupDigest)} 生成，与前端二次确认弹窗展示的一致；
     * 经 {@link NapCatService#sendGroupMessage(String, String)} 发到 QQ 群，NapCat 不可用 / 未登录 / 发送失败 → 503。</p>
     *
     * <p>成功返回 {@code data = {pushed, groupId, digestDate, text, napcatMessageId}}；
     * 成功与失败都会写审计（action = {@code AI_SUMMARY_DIGEST_PUSH}）。</p>
     */
    @PostMapping("/{groupId}/digest/push")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<?>> pushGroupDigest(
            @PathVariable String groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        String username = securityHelper.getCurrentUsername();

        try {
            // 开关 / 群白名单：不满足直接 403（BizException 由 GlobalExceptionHandler 统一转 ApiResponse）
            groupDigestService.assertDigestAllowed(groupId);
        } catch (BizException e) {
            auditLogService.log(username, "AI_SUMMARY_DIGEST_PUSH", "group:" + groupId, "FAIL",
                    "推送 " + targetDate + " 速览到群 " + groupId + " 被拒绝：" + e.getMessage());
            throw e;
        }

        // 不隐式生成：当天没有日报 → 400
        Optional<GroupDigest> existing = groupDigestService.findByDate(groupId, targetDate);
        if (existing.isEmpty()) {
            auditLogService.log(username, "AI_SUMMARY_DIGEST_PUSH", "group:" + groupId, "FAIL",
                    "推送 " + targetDate + " 速览到群 " + groupId + " 失败：该群当天还没有速览");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "该群当天还没有速览，请先生成"));
        }

        GroupDigest digest = existing.get();
        String text = groupDigestService.buildPushText(digest);
        String digestDate = digest.getDigestDate() == null ? targetDate.toString() : digest.getDigestDate().toString();

        NapCatService.SendGroupMessageResult sendResult = napCatService.sendGroupMessage(groupId, text);
        if (sendResult == null || !sendResult.isSuccess()) {
            String detail = (sendResult == null || sendResult.getErrorMessage() == null) ? "未知原因" : sendResult.getErrorMessage();
            log.warn("推送群日报失败 groupId={}, date={}: {}", groupId, digestDate, detail);
            auditLogService.log(username, "AI_SUMMARY_DIGEST_PUSH", "group:" + groupId, "FAIL",
                    "推送 " + digestDate + " 速览到群 " + groupId + " 失败：" + detail);
            // 提示文案与契约保持一致（具体原因只进日志/审计，不泄漏内部细节给前端）
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "NapCat 未登录或不可用，推送失败"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pushed", true);
        data.put("groupId", groupId);
        data.put("digestDate", digestDate);
        data.put("text", text);
        data.put("napcatMessageId", sendResult.getMessageId());

        auditLogService.log(username, "AI_SUMMARY_DIGEST_PUSH", "group:" + groupId, "SUCCESS",
                "推送 " + digestDate + " 速览到群 " + groupId + " 成功, napcatMessageId=" + sendResult.getMessageId());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 从网页向该 QQ 群发送一条文本消息：{@code POST /api/groups/{groupId}/send}，body {@code {"text":"..."}}。
     *
     * <p>权限：登录 + 该群对当前用户可见（管理员不受限），即"有群可见性的用户都能发"。</p>
     * <p>风控：每用户 10 次/分钟（超限 429）；文本 1~2000 字（空 → 400，超长 → 400）。</p>
     * <p>发送身份是 <b>NapCat 登录的机器人账号</b>，不是网页登录用户；消息会真实发到群里、不可撤回。
     * 本地不插入消息记录 —— NapCat 会把机器人自己发的消息通过 webhook 回传，由正常链路入库（避免重复）。</p>
     * <p>成功返回 {@code data = {sent, groupId, napcatMessageId, length}}；成功与失败都写审计。</p>
     */
    @PostMapping("/{groupId}/send")
    public ResponseEntity<ApiResponse<?>> sendGroupText(
            @PathVariable String groupId,
            @RequestBody(required = false) Map<String, Object> body) {

        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }

        String username = securityHelper.getCurrentUsername();
        String text = (body == null || body.get("text") == null) ? "" : String.valueOf(body.get("text")).trim();
        if (text.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, "消息内容不能为空"));
        }
        if (text.length() > MAX_GROUP_SEND_LENGTH) {
            auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "FAIL",
                    "发送被拒绝：内容超过 " + MAX_GROUP_SEND_LENGTH + " 字（实际 " + text.length() + " 字）");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "消息过长，最多 " + MAX_GROUP_SEND_LENGTH + " 字"));
        }

        Long userId = securityHelper.getCurrentUserId();
        if (!rateLimiterService.isAllowed("group-send:" + userId, 10, 1)) {
            auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "FAIL", "发送被限流（每分钟最多 10 条）");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, "发送过于频繁，请稍后再试（每分钟最多 10 条）"));
        }

        NapCatService.SendGroupMessageResult sendResult = napCatService.sendGroupMessage(groupId, text);
        if (sendResult == null || !sendResult.isSuccess()) {
            String detail = (sendResult == null || sendResult.getErrorMessage() == null) ? "未知原因" : sendResult.getErrorMessage();
            log.warn("网页发送群消息失败 groupId={}: {}", groupId, detail);
            auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "FAIL", "发送失败：" + detail);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "NapCat 未登录或不可用，发送失败"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sent", true);
        data.put("groupId", groupId);
        data.put("napcatMessageId", sendResult.getMessageId());
        data.put("length", text.length());

        // 审计只记长度与预览，不整条落库（群消息本身已在 messages 表）
        String preview = text.length() > 30 ? text.substring(0, 30) + "…" : text;
        auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "SUCCESS",
                "发送 " + text.length() + " 字：[" + preview + "], napcatMessageId=" + sendResult.getMessageId());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 群日报三个接口共用的权限校验，口径与 {@code MessageController.getMessagesByGroupId} 一致：
     * 未登录 → 401；管理员跳过群校验；未绑定 QQ → 403「请先绑定QQ账号」；无群可见性 → 403「无权访问该群聊」。
     *
     * @return 校验通过返回 null；否则返回可直接回给前端的错误响应
     */
    private ResponseEntity<ApiResponse<?>> checkGroupAccess(String groupId) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未登录"));
        }
        // 管理员不受群可见性限制
        if (securityHelper.isAdmin()) {
            return null;
        }
        List<String> userQqList = securityHelper.getCurrentUserQqBindings();
        if (userQqList.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "请先绑定QQ账号"));
        }
        if (!securityHelper.hasGroupAccess(groupId, userQqList)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "无权访问该群聊"));
        }
        return null;
    }
}
