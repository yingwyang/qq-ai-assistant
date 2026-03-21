package com.qqai.config;

import com.qqai.websocket.NapCatWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket配置 - 接收NapCat消息
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private NapCatWebSocketHandler napCatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // NapCat WebSocket反向连接端点
        registry.addHandler(napCatWebSocketHandler, "/ws")
                .setAllowedOrigins("*");
    }
}
