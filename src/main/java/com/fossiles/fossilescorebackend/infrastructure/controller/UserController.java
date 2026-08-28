package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.UserRequest;
import com.fossiles.fossilescorebackend.application.dto.request.UserStatusRequest;
import com.fossiles.fossilescorebackend.application.dto.request.UserUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ConnectedUserResponse;
import com.fossiles.fossilescorebackend.application.dto.response.UserActivityLogResponse;
import com.fossiles.fossilescorebackend.application.dto.response.UserResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.UserActivityService;
import com.fossiles.fossilescorebackend.application.service.UserService;
import com.fossiles.fossilescorebackend.infrastructure.service.S3StorageService;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserActivityService userActivityService;
    private final JwtUtil jwtUtil;
    private final S3StorageService s3StorageService;

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        authorizeUserManagement();
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/connected")
    public ResponseEntity<List<ConnectedUserResponse>> getConnectedUsers(
            @RequestParam(name = "windowMinutes", required = false, defaultValue = "5") Integer windowMinutes) {
        authorizeUserManagement();
        return ResponseEntity.ok(userActivityService.getConnectedUsers(windowMinutes));
    }

    @GetMapping("/{id}/recent-actions")
    public ResponseEntity<List<UserActivityLogResponse>> getUserRecentActions(@PathVariable Long id) {
        authorizeUserManagement();
        return ResponseEntity.ok(userActivityService.getRecentActions(id));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() throws ResourceNotFoundException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResourceNotFoundException("User not authenticated");
        }
        
        String username = authentication.getName();
        UserResponse user = userService.getUserByUsername(username);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) throws ResourceNotFoundException {
        authorizeUserManagement();
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserRequest request) throws BusinessException {
        authorizeUserManagement();
        UserResponse created = userService.createUser(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.getId())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) throws BusinessException, ResourceNotFoundException {
        authorizeUserManagement();
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<UserResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody UserStatusRequest request) throws BusinessException, ResourceNotFoundException {
        authorizeUserManagement();
        return ResponseEntity.ok(userService.changeUserStatus(id, request));
    }

    @PostMapping("/{id}/profile-image")
    public ResponseEntity<UserResponse> uploadUserProfileImage(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file) throws ResourceNotFoundException, IOException {
        authorizeUserManagement();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debe seleccionar una imagen");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo debe ser una imagen");
        }

        S3StorageService.UploadResult uploadResult = s3StorageService.uploadImage(file);
        return ResponseEntity.ok(userService.updateUserProfileImage(id, uploadResult.getUrl()));
    }

    private void authorizeUserManagement() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication != null ? authentication.getName() : null;
        if (!userService.canManageUsers(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo ADMIN o RRHH pueden gestionar usuarios");
        }
    }
}

