package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.LoginRequest;
import com.fossiles.fossilescorebackend.application.dto.response.LoginResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.port.UserRepositoryPort;
import com.fossiles.fossilescorebackend.domain.model.User;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserRefreshTokenEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.EncryptionUtil;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Servicio de autenticación
 * Maneja el login con JWT y encriptación de contraseñas
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepositoryPort userRepositoryPort;
    private final JwtUtil jwtUtil;
    private final EncryptionUtil encryptionUtil;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    /**
     * Autentica un usuario y genera un token JWT
     */
    public LoginResponse login(LoginRequest request) throws BusinessException {
        User user = authenticateCredentials(request);
        return buildLoginResponse(user, "mobile");
    }

    public LoginResponse refresh(String plainRefreshToken) throws BusinessException {
        UserRefreshTokenEntity stored = refreshTokenService.findValid(plainRefreshToken);
        User user = userRepositoryPort.findById(stored.getUserId())
                .orElseThrow(() -> new BusinessException("Usuario no encontrado."));
        if (!"active".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException("User account is not active");
        }
        String newRefresh = refreshTokenService.rotate(stored, stored.getDeviceLabel());
        return buildLoginResponseWithRefresh(user, newRefresh);
    }

    public void logout(String plainRefreshToken) {
        refreshTokenService.revokeByPlainToken(plainRefreshToken);
    }

    private User authenticateCredentials(LoginRequest request) throws BusinessException {
        String decryptedPassword;
        try {
            decryptedPassword = encryptionUtil.decrypt(request.getEncryptedPassword());
        } catch (Exception e) {
            throw new BusinessException("Error al procesar la contraseña encriptada: " + e.getMessage());
        }

        Optional<User> userOpt = userRepositoryPort.findByUsername(request.getUsernameOrEmail());
        if (userOpt.isEmpty()) {
            userOpt = userRepositoryPort.findByEmail(request.getUsernameOrEmail());
        }

        if (userOpt.isEmpty()) {
            throw new BusinessException("Usuario o Contraseña Invalidos");
        }

        User user = userOpt.get();

        if (!"active".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException("User account is not active");
        }

        if (!passwordEncoder.matches(decryptedPassword, user.getPassword())) {
            throw new BusinessException("Usuario o Contraseña Invalidos");
        }

        return user;
    }

    private LoginResponse buildLoginResponse(User user, String deviceLabel) {
        String refreshToken = refreshTokenService.createAndPersist(user, deviceLabel);
        return buildLoginResponseWithRefresh(user, refreshToken);
    }

    private LoginResponse buildLoginResponseWithRefresh(User user, String refreshToken) {
        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getEmail());
        return LoginResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .expiresIn(jwtUtil.getAccessExpirationMs())
                .type("Bearer")
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .status(user.getStatus())
                .build();
    }

    /**
     * Valida un token JWT
     */
    @Transactional(readOnly = true)
    public boolean validateToken(String token) {
        try {
            String username = jwtUtil.extractUsername(token);
            Optional<User> userOpt = userRepositoryPort.findByUsername(username);

            if (userOpt.isEmpty()) {
                return false;
            }

            User user = userOpt.get();
            return jwtUtil.validateToken(token, user.getUsername());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Obtiene el usuario desde un token JWT
     */
    @Transactional(readOnly = true)
    public Optional<User> getUserFromToken(String token) {
        try {
            String username = jwtUtil.extractUsername(token);
            return userRepositoryPort.findByUsername(username);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
