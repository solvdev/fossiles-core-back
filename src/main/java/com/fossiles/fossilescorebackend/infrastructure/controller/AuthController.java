package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.LoginRequest;
import com.fossiles.fossilescorebackend.application.dto.request.LogoutRequest;
import com.fossiles.fossilescorebackend.application.dto.request.RefreshTokenRequest;
import com.fossiles.fossilescorebackend.application.dto.response.LoginResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador de autenticación
 * Endpoints para login y validación de tokens
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Endpoint de login
     * POST /api/auth/login
     * 
     * Recibe username/email y contraseña encriptada
     * Retorna token JWT si las credenciales son válidas
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) throws BusinessException {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint para validar un token
     * GET /api/auth/validate
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request)
            throws BusinessException {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request) {
        if (request != null && request.getRefreshToken() != null) {
            authService.logout(request.getRefreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/validate")
    public ResponseEntity<Boolean> validateToken(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(false);
        }

        String token = authHeader.substring(7);
        boolean isValid = authService.validateToken(token);
        
        return isValid 
            ? ResponseEntity.ok(true)
            : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(false);
    }
}

