package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;

/**
 * Filtro JWT que intercepta las peticiones y valida el token
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String STREAM_PATH = "/api/system-announcements/stream";

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        String jwt = null;
        String username = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        } else if (STREAM_PATH.equals(request.getRequestURI().substring(request.getContextPath().length()))) {
            // EventSource no envía encabezados: el token por URL se acepta solo en el stream de anuncios.
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isBlank()) {
                jwt = tokenParam.trim();
            }
        }

        // Si no hay token, continuar sin autenticación
        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Extraer username del token
            username = jwtUtil.extractUsername(jwt);

            // Si hay username y no hay autenticación en el contexto
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // Validar el token
                if (jwtUtil.validateToken(jwt, username)) {
                    // El estado actual del usuario prevalece sobre la vigencia del JWT emitido.
                    var user = userRepository.findByUsername(username).orElse(null);
                    if (user == null || !"active".equalsIgnoreCase(user.getStatus())
                            || !Objects.equals(user.getId(), jwtUtil.extractUserId(jwt))) {
                        reject(request, response, filterChain, "Usuario sin acceso activo.");
                        return;
                    }
                    // Crear autenticación
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            logger.debug("JWT rechazado", e);
            reject(request, response, filterChain, "Token de acceso inválido.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    // En una ruta pública un token vencido no bloquea: la petición sigue como anónima. En las demás responde 401.
    private void reject(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain, String message)
            throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        if (SecurityConfig.isPublicRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message);
    }
}

