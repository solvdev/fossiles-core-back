package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.LoginRequest;
import com.fossiles.fossilescorebackend.application.dto.response.LoginResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.port.UserRepositoryPort;
import com.fossiles.fossilescorebackend.domain.model.User;
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

    /**
     * Autentica un usuario y genera un token JWT
     * 
     * @param request LoginRequest con username/email y contraseña encriptada
     * @return LoginResponse con el token JWT y datos del usuario
     * @throws BusinessException si las credenciales son inválidas
     */
    public LoginResponse login(LoginRequest request) throws BusinessException {
        // 1. Desencriptar la contraseña recibida del frontend
        String decryptedPassword;
        try {
            decryptedPassword = encryptionUtil.decrypt(request.getEncryptedPassword());
        } catch (Exception e) {
            throw new BusinessException("Error al procesar la contraseña encriptada: " + e.getMessage());
        }

        // 2. Buscar usuario por username o email
        Optional<User> userOpt = userRepositoryPort.findByUsername(request.getUsernameOrEmail());
        if (userOpt.isEmpty()) {
            userOpt = userRepositoryPort.findByEmail(request.getUsernameOrEmail());
        }

        if (userOpt.isEmpty()) {
            throw new BusinessException("Usuario o Contraseña Invalidos");
        }

        User user = userOpt.get();

        // 3. Verificar que el usuario esté activo
        if (!"active".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException("User account is not active");
        }

        // 4. Verificar la contraseña (comparar la desencriptada con la hasheada en BD)
        if (!passwordEncoder.matches(decryptedPassword, user.getPassword())) {
            throw new BusinessException("Usuario o Contraseña Invalidos");
        }

        // 5. Generar token JWT
        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getEmail());

        // 6. Construir respuesta
        return LoginResponse.builder()
                .token(token)
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

