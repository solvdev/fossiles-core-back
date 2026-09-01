package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.UserActivityService;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityFilter extends OncePerRequestFilter {

    private final UserActivityService userActivityService;
    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. Extraer username del contexto de seguridad o directamente del token JWT
        String username = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equalsIgnoreCase(auth.getName())) {
                username = auth.getName();
            }
        } catch (Exception ignored) {}

        if (username == null) {
            try {
                String authHeader = request.getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    username = jwtUtil.extractUsername(token);
                } else {
                    String tokenParam = request.getParameter("token");
                    if (tokenParam != null && !tokenParam.isBlank()) {
                        username = jwtUtil.extractUsername(tokenParam.trim());
                    }
                }
            } catch (Exception ignored) {}
        }

        filterChain.doFilter(request, response);

        // 2. Registrar actividad si la petición fue procesada con éxito (status < 400 o relevante)
        try {
            if (username != null && !"anonymousUser".equalsIgnoreCase(username)) {
                String path = request.getRequestURI();
                if (!isIgnoredPath(path)) {
                    String method = request.getMethod();
                    String ip = extractClientIp(request);
                    String userAgent = request.getHeader("User-Agent");
                    userActivityService.recordActivity(username, method, path, ip, userAgent);
                }
            }
        } catch (Exception e) {
            log.trace("Error capturando actividad de usuario: {}", e.getMessage());
        }
    }

    private boolean isIgnoredPath(String path) {
        if (path == null) return true;
        return path.contains("/api/users/connected") ||
                path.contains("/recent-actions") ||
                path.contains("/api/system-announcements") ||
                path.contains("/actuator") ||
                path.contains("/favicon.ico") ||
                path.contains("/error");
    }

    private String extractClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty() || "unknown".equalsIgnoreCase(xfHeader)) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}


