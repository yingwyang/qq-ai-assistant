package com.qqai.controller;

import com.fasterxml.jackson.databind.node.ArrayNode;
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
import com.qqai.service.OutboundMediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** 网页发送媒体的落盘与消息段编排（replyToId → OneBot message_id 的解析也在这里） */
    @Autowired
    private OutboundMediaService outboundMediaService;

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
        // 人层：日报也用触发者选定的人格
        Long personaUserId = null;
        try {
            personaUserId = securityHelper.getCurrentUserId();
        } catch (Exception ignored) {
            // 取不到就当系统调用（用默认人格）
        }
        GroupDigest digest = groupDigestService.generateDailyDigest(groupId, targetDate, force, personaUserId);
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

        Long requester = null;
        try {
            requester = securityHelper.getCurrentUserId();
        } catch (Exception ignored) {
            // 系统调用，无用户上下文
        }
        messageQueueService.sendGroupDigest(new GroupDigestPayload(groupId, targetDate.toString(), force, requester));
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
     * 从网页向该 QQ 群发送一条消息：{@code POST /api/groups/{groupId}/send}。
     *
     * <p>请求体（{@code replyToId} / {@code atQqs} 为可选新增字段，旧客户端只传 {@code text} 行为不变）：</p>
     * <pre>{@code
     * { "text": "内容", "replyToId": 100602, "atQqs": ["123456", "7890"] }
     * }</pre>
     *
     * <ul>
     *   <li>{@code text}：允许为空，但「text 非空 或 atQqs 非空」必须成立，否则 400「消息内容不能为空」；≤2000 字；</li>
     *   <li>{@code replyToId}：<b>数据库自增 id</b>（前端消息列表里的 id）。取该消息的 {@code message_id}
     *       （OneBot id）拼 reply 段。消息不存在 → 400「被回复的消息不存在」；
     *       不属于 URL 里的 groupId → 400「被回复的消息不属于该群」；{@code message_id} 为空 → 忽略 reply 段（不报错）；</li>
     *   <li>{@code atQqs}：QQ 号字符串数组，最多 10 个（超出只取前 10 并 warn）；</li>
     *   <li><b>段顺序固定</b>：{@code reply? → at×N → text?}（text 为空则不加 text 段）。</li>
     * </ul>
     *
     * <p>权限：登录 + 该群对当前用户可见（管理员不受限），即"有群可见性的用户都能发"。</p>
     * <p>风控：每用户 10 次/分钟（超限 429，与 {@code send-media} 共用 {@code group-send:<userId>} 键）。</p>
     * <p>发送身份是 <b>NapCat 登录的机器人账号</b>，不是网页登录用户；消息会真实发到群里、不可撤回。
     * 本地不插入消息记录 —— NapCat 会把机器人自己发的消息通过 webhook 回传，由正常链路入库（避免重复）。</p>
     * <p>成功返回 {@code data = {sent, groupId, napcatMessageId, length, segmentTypes}}（{@code segmentTypes} 为新增字段，
     * 其余字段与扩展前一致）；成功与失败都写审计，detail 里带上段类型与 at 个数。</p>
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
        Long replyToId = parseReplyToId(body);
        List<String> atQqs = parseAtQqs(body);

        // 契约：text 非空 或 atQqs 非空（纯 @ / 纯引用也能发）
        if (text.isEmpty() && atQqs.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, "消息内容不能为空"));
        }
        if (text.length() > MAX_GROUP_SEND_LENGTH) {
            auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "FAIL",
                    "发送被拒绝：内容超过 " + MAX_GROUP_SEND_LENGTH + " 字（实际 " + text.length() + " 字）");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "消息过长，最多 " + MAX_GROUP_SEND_LENGTH + " 字"));
        }

        // 段组装（reply → at×N → text）：replyToId 非法时 400；message_id 为空则忽略 reply 段
        String replyMessageId;
        ArrayNode segments;
        try {
            replyMessageId = outboundMediaService.resolveReplyMessageId(groupId, replyToId);
            segments = outboundMediaService.buildTextSegments(replyMessageId, atQqs, text);
        } catch (BizException e) {
            // 审计留痕后原样上抛：业务异常的状态码与文案由 GlobalExceptionHandler 统一输出
            auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "FAIL", "发送被拒绝：" + e.getMessage());
            throw e;
        }
        List<String> segmentTypes = outboundMediaService.segmentTypes(segments);

        Long userId = securityHelper.getCurrentUserId();
        if (!rateLimiterService.isAllowed("group-send:" + userId, 10, 1)) {
            auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "FAIL", "发送被限流（每分钟最多 10 条）");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, "发送过于频繁，请稍后再试（每分钟最多 10 条）"));
        }

        NapCatService.SendGroupMessageResult sendResult = napCatService.sendGroupSegments(groupId, segments);
        if (sendResult == null || !sendResult.isSuccess()) {
            String detail = (sendResult == null || sendResult.getErrorMessage() == null) ? "未知原因" : sendResult.getErrorMessage();
            log.warn("网页发送群消息失败 groupId={}: {}", groupId, detail);
            auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "FAIL",
                    "发送失败：" + detail + buildSegmentDetail(segmentTypes, atQqs.size()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "NapCat 未登录或不可用，发送失败"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sent", true);
        data.put("groupId", groupId);
        data.put("napcatMessageId", sendResult.getMessageId());
        data.put("length", text.length());
        // 新增字段：本次实际发出的段类型，如 ["reply","at","text"]
        data.put("segmentTypes", segmentTypes);

        // 审计只记长度与预览，不整条落库（群消息本身已在 messages 表）；另带上段类型与 at 个数
        String preview = text.length() > 30 ? text.substring(0, 30) + "…" : text;
        auditLogService.log(username, "GROUP_SEND", "group:" + groupId, "SUCCESS",
                "发送 " + text.length() + " 字：[" + preview + "]" + buildSegmentDetail(segmentTypes, atQqs.size())
                        + ", napcatMessageId=" + sendResult.getMessageId());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 从网页向该 QQ 群发送一个文件（图片 / 其他文件）：{@code POST /api/groups/{groupId}/send-media}，
     * {@code Content-Type: multipart/form-data}。
     *
     * <table border="1">
     *   <caption>表单字段</caption>
     *   <tr><th>字段</th><th>必填</th><th>说明</th></tr>
     *   <tr><td>{@code file}</td><td>是</td><td>要发送的文件，≤20MB（超出 400「文件过大，最大 20MB」）</td></tr>
     *   <tr><td>{@code text}</td><td>否</td><td>附带文字；图片时与图片同一条消息发出，其他文件时作为一条独立文本消息先发</td></tr>
     *   <tr><td>{@code replyToId}</td><td>否</td><td>数据库自增 id，语义与 {@code /send} 完全一致</td></tr>
     *   <tr><td>{@code atQqs}</td><td>否</td><td>逗号分隔的 QQ 号字符串（multipart 里只能传字符串），最多 10 个</td></tr>
     * </table>
     *
     * <p><b>图片判定</b>：扩展名（jpg/jpeg/png/gif/webp/bmp）与 Content-Type（{@code image/*}）同时满足才走图片分支；
     * ≤4MB 用 {@code base64://} 图片段，&gt;4MB 用本地绝对路径图片段（NapCat 与后端同机）。
     * 段顺序 {@code reply? → at×N → image → text?}。</p>
     *
     * <p><b>其他文件</b>：先（可选）发一条 {@code reply? → at×N → text?} 文本消息，
     * 再调 NapCat {@code upload_group_file}（body {@code {"group_id":<数字>,"file":"<本地绝对路径>","name":"<原始文件名>"}}）。
     * 文件落盘于 {@code backend/uploads/outbound/<groupId>/<uuid>.<ext>}（已被 .gitignore 覆盖）。</p>
     *
     * <p>权限与限流同 {@code /send}（同一个 {@code group-send:<userId>} 键，10 次/分钟）；
     * 审计 action 为 {@code GROUP_SEND_MEDIA}。
     * 成功返回 {@code data = {sent, groupId, kind, fileName, size, napcatMessageId, segmentTypes}}
     * （其他文件且带文字时额外含 {@code textMessageId}）。
     * NapCat 不可用 / 发送失败 → 503「NapCat 未登录或不可用，发送失败」。</p>
     */
    @PostMapping(value = "/{groupId}/send-media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<?>> sendGroupMedia(
            @PathVariable String groupId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "replyToId", required = false) String replyToIdRaw,
            @RequestParam(value = "atQqs", required = false) String atQqsRaw) {

        ResponseEntity<ApiResponse<?>> denied = checkGroupAccess(groupId);
        if (denied != null) {
            return denied;
        }

        String username = securityHelper.getCurrentUsername();
        String content = text == null ? "" : text.trim();
        if (content.length() > MAX_GROUP_SEND_LENGTH) {
            auditLogService.log(username, "GROUP_SEND_MEDIA", "group:" + groupId, "FAIL",
                    "发送被拒绝：文字超过 " + MAX_GROUP_SEND_LENGTH + " 字（实际 " + content.length() + " 字）");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "消息过长，最多 " + MAX_GROUP_SEND_LENGTH + " 字"));
        }

        Long replyToId = parseReplyToId(replyToIdRaw);
        List<String> atQqs = outboundMediaService.normalizeAtQqs(splitQqList(atQqsRaw));

        try {
            Map<String, Object> data =
                    outboundMediaService.sendMedia(groupId, file, content, replyToId, atQqs);
            // 与其他发消息接口同一处口径：审计 detail 记 kind/文件名/大小/段类型/napcatMessageId
            auditLogService.log(username, "GROUP_SEND_MEDIA", "group:" + groupId, "SUCCESS",
                    "发送媒体 kind=" + data.get("kind")
                            + ", fileName=" + safeForAudit(String.valueOf(data.get("fileName")))
                            + ", size=" + data.get("size") + "B"
                            + ", segmentTypes=" + data.get("segmentTypes")
                            + ", napcatMessageId=" + data.get("napcatMessageId"));
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (BizException e) {
            return handleSendMediaFailure(groupId, username, e.getMessage(), e.getCode());
        } catch (Exception e) {
            log.error("网页发送媒体异常 groupId={}: {}", groupId, e.getMessage(), e);
            return handleSendMediaFailure(groupId, username, e.getMessage(), 500);
        }
    }

    /**
     * 媒体发送失败的统一收尾：写 FAIL 审计 + 按业务码回错误体。
     * 仅 {@link BizException} 的 400/401/403/429 原样返回，其余（含 500）统一按 NapCat 不可用 503 口径。
     */
    private ResponseEntity<ApiResponse<?>> handleSendMediaFailure(String groupId, String username,
                                                                 String reason, int code) {
        String detail = (reason == null || reason.isBlank()) ? "未知原因" : reason;
        if (code == 400 || code == 401 || code == 403 || code == 429) {
            log.warn("网页发送媒体被拒绝 groupId={}, code={}: {}", groupId, code, detail);
            auditLogService.log(username, "GROUP_SEND_MEDIA", "group:" + groupId, "FAIL",
                    "发送被拒绝：" + detail);
            return ResponseEntity.status(code).body(ApiResponse.error(code, detail));
        }
        log.warn("网页发送媒体失败 groupId={}: {}", groupId, detail);
        auditLogService.log(username, "GROUP_SEND_MEDIA", "group:" + groupId, "FAIL", "发送失败：" + detail);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "NapCat 未登录或不可用，发送失败"));
    }

    /**
     * 解析 {@code replyToId}（JSON 请求体）：数字 / 数字字符串都接受，非法值返回 null（等同未传）。
     */
    private Long parseReplyToId(Map<String, Object> body) {
        if (body == null || body.get("replyToId") == null) {
            return null;
        }
        return parseReplyToId(String.valueOf(body.get("replyToId")));
    }

    /**
     * 解析 {@code replyToId}（multipart 表单）：字符串形式，空白或非法值返回 null（等同未传）。
     */
    private Long parseReplyToId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(trimmed);
        } catch (NumberFormatException e) {
            log.warn("replyToId 不是合法数字，按未传处理: {}", trimmed);
            return null;
        }
    }

    /**
     * 解析请求体里的 {@code atQqs}：只接受数组（multipart 用 {@link #splitQqList(String)}）。
     * 非数组、非字符串元素一律忽略。
     */
    private List<String> parseAtQqs(Map<String, Object> body) {
        if (body == null || body.get("atQqs") == null) {
            return new ArrayList<>();
        }
        Object raw = body.get("atQqs");
        if (!(raw instanceof List<?> list)) {
            log.warn("atQqs 不是数组，已忽略: {}", raw.getClass().getSimpleName());
            return new ArrayList<>();
        }
        List<String> qqs = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String qq = String.valueOf(item).trim();
            if (!qq.isEmpty()) {
                qqs.add(qq);
            }
        }
        return qqs;
    }

    /** 把 multipart 里的 {@code "123456,7890"} 拆成 QQ 号列表（逗号 / 换行 / 分号分隔，去空白） */
    private List<String> splitQqList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 审计 detail 片段：段类型 + at 个数（口径：审计里带上段类型与 at 个数） */
    private String buildSegmentDetail(List<String> segmentTypes, int atCount) {
        if (segmentTypes == null || segmentTypes.isEmpty()) {
            return ", segmentTypes=[], atCount=" + atCount;
        }
        return ", segmentTypes=" + segmentTypes + ", atCount=" + atCount;
    }

    /** 审计 detail 里去掉可能破坏日志可读性的换行/引号 */
    private String safeForAudit(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\r\\n\"]", "_");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) + "…" : cleaned;
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
