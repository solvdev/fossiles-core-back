package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Utilidad para obtener información del usuario actual autenticado
 */
@Component
@RequiredArgsConstructor
public class SecurityUtil {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    /**
     * Obtiene el ID del usuario actual desde el contexto de seguridad
     * @return ID del usuario o null si no está autenticado
     */
    public Long getCurrentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                String username = authentication.getName();
                // Obtener el usuario por username
                Optional<UserEntity> userOpt = userRepository.findByUsername(username);
                if (userOpt.isPresent()) {
                    return userOpt.get().getId();
                }
            }
        } catch (Exception e) {
            // Si hay algún error, retornar null
        }
        return null;
    }

    /**
     * Obtiene el usuario actual completo
     * @return Usuario actual o null si no está autenticado
     */
    public Optional<UserEntity> getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                String username = authentication.getName();
                return userRepository.findByUsername(username);
            }
        } catch (Exception e) {
            // Si hay algún error, retornar empty
        }
        return Optional.empty();
    }

    /**
     * Obtiene el username del usuario actual
     * @return Username o null si no está autenticado
     */
    public String getCurrentUsername() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                return authentication.getName();
            }
        } catch (Exception e) {
            // Si hay algún error, retornar null
        }
        return null;
    }
}

