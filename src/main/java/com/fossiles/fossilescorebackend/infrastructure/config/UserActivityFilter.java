package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.UserActivityService;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        filterChain.doFilter(request, response);

        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equalsIgnoreCase(auth.getName())) {
                String path = request.getRequestURI();
                if (!isIgnoredPath(path)) {
                    String method = request.getMethod();
                    String ip = extractClientIp(request);
                    String userAgent = request.getHeader("User-Agent");
                    userActivityService.recordActivity(auth.getName(), method, path, ip, userAgent);
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
