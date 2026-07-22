package com.qqai.security;

import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (!StringUtils.hasText(jwt)) {
                filterChain.doFilter(request, response);
                return;
            }

            // Token present but invalid - return 401 to trigger frontend re-login
            if (!jwtUtil.validateToken(jwt)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"登录已过期，请重新登录\",\"code\":401}");
                return;
            }

            String jti = jwtUtil.getJtiFromToken(jwt);
            if (tokenBlacklistService.isJtiBlacklisted(jti)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"登录已过期，请重新登录\",\"code\":401}");
                return;
            }

            Long userId = jwtUtil.getUserIdFromToken(jwt);
            Integer tvClaim = jwtUtil.getTokenVersionFromToken(jwt);
            String username = jwtUtil.getUsernameFromToken(jwt);
            String role = jwtUtil.getRoleFromToken(jwt);

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"登录已过期，请重新登录\",\"code\":401}");
                return;
            }

            User user = userOpt.get();
            Integer dbTv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
            if (!dbTv.equals(tvClaim)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"登录已过期，请重新登录\",\"code\":401}");
                return;
            }

            AuthPrincipal principal = new AuthPrincipal(userId, username, role, tvClaim);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"登录已过期，请重新登录\",\"code\":401}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // Support token as query parameter (for file downloads)
        String queryToken = request.getParameter("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }
}
