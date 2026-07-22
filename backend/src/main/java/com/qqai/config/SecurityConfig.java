package com.qqai.config;

import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.websocket.FrontendMessageWebSocketHandler;
import com.qqai.websocket.NapCatWebSocketHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableWebSocket
public class SecurityConfig implements WebSocketConfigurer {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private NapCatWebSocketHandler napCatWebSocketHandler;

    @Autowired
    private FrontendMessageWebSocketHandler frontendMessageWebSocketHandler;

    @Autowired
    private JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"未登录或登录已过期\",\"code\":401}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/register").permitAll()
                .requestMatchers("/api/system/health").permitAll()
                .requestMatchers("/").permitAll()
                .requestMatchers("/webhook").permitAll()
                .requestMatchers("/api/avatar/**").permitAll()
                .requestMatchers("/uploads/**").permitAll()
                .requestMatchers("/images/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/system/start-astrbot", "/api/system/stop-astrbot", "/api/system/restart-astrbot").hasRole("ADMIN")
                .requestMatchers("/api/system/start-gptsovits", "/api/system/stop-gptsovits", "/api/system/restart-gptsovits").hasRole("ADMIN")
                .requestMatchers("/api/system/tts").hasRole("ADMIN")
                .requestMatchers("/api/system/napcat/qrcode-image").permitAll()
                .requestMatchers("/api/system/napcat/login-status").permitAll()
                .requestMatchers("/api/system/component-status").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(napCatWebSocketHandler, "/ws")
                .setAllowedOrigins("*");

        registry.addHandler(frontendMessageWebSocketHandler, "/ws/messages")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
