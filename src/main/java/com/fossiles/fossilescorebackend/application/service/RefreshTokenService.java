package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.domain.model.User;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserRefreshTokenEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRefreshTokenRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    private final UserRefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;

    public String createAndPersist(User user, String deviceLabel) {
        String plain = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(jwtUtil.getRefreshExpirationMs() / 1000);

        UserRefreshTokenEntity entity = UserRefreshTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(hashToken(plain))
                .expiresAt(expiresAt)
                .revoked(false)
                .deviceLabel(deviceLabel)
                .build();
        refreshTokenRepository.save(entity);
        return plain;
    }

    @Transactional(readOnly = true)
    public UserRefreshTokenEntity findValid(String plainRefreshToken) throws BusinessException {
        if (plainRefreshToken == null || plainRefreshToken.isBlank()) {
            throw new BusinessException("Refresh token requerido.");
        }
        String hash = hashToken(plainRefreshToken.trim());
        UserRefreshTokenEntity entity = refreshTokenRepository.findByTokenHashAndRevokedFalse(hash)
                .orElseThrow(() -> new BusinessException("Sesión inválida o expirada. Inicia sesión nuevamente."));
        if (entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Sesión inválida o expirada. Inicia sesión nuevamente.");
        }
        return entity;
    }

    public void revoke(UserRefreshTokenEntity entity) {
        entity.setRevoked(true);
        refreshTokenRepository.save(entity);
    }

    public void revokeByPlainToken(String plainRefreshToken) {
        if (plainRefreshToken == null || plainRefreshToken.isBlank()) {
            return;
        }
        String hash = hashToken(plainRefreshToken.trim());
        refreshTokenRepository.findByTokenHashAndRevokedFalse(hash).ifPresent(this::revoke);
    }

    /** Rotación: revoca el anterior y emite uno nuevo para el mismo usuario. */
    public String rotate(UserRefreshTokenEntity existing, String deviceLabel) {
        revoke(existing);
        User user = User.builder().id(existing.getUserId()).build();
        return createAndPersist(user, deviceLabel);
    }

    private static String hashToken(String plain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
