package com.qqai.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * NapCat Webhook 控制器 - 转发到 RootWebhookController 处理
 */
@RestController
@RequestMapping("/api/napcat")
public class NapCatWebhookController {

    @Autowired
    private RootWebhookController rootWebhookController;

    /**
     * 接收 NapCat 的消息推送 - 根路径，转发到 RootWebhookController 处理
     */
    @PostMapping("")
    public ResponseEntity<?> receiveMessageRoot(
            @RequestBody String payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Self-ID", required = false) String selfId) {
        // 转发到 RootWebhookController 处理，以支持媒体下载和转换
        return rootWebhookController.receiveMessageRoot(payload, authHeader, null, null, selfId, null);
    }

    /**
     * 接收 NapCat 的消息推送 - webhook 路径，转发到 RootWebhookController 处理
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> receiveMessage(
            @RequestBody String payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Self-ID", required = false) String selfId) {
        // 转发到 RootWebhookController 处理，以支持媒体下载和转换
        return rootWebhookController.receiveMessageRoot(payload, authHeader, null, null, selfId, null);
    }
}
