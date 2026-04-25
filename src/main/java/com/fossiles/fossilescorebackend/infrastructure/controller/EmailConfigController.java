package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.EmailConfigRequest;
import com.fossiles.fossilescorebackend.application.dto.response.EmailConfigResponse;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmailConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmailConfigRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/email-config")
@RequiredArgsConstructor
public class EmailConfigController {

    private final EmailConfigRepository emailConfigRepository;

    @GetMapping
    public ResponseEntity<List<EmailConfigResponse>> getAll() {
        List<EmailConfigResponse> configs = emailConfigRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmailConfigResponse> getById(@PathVariable Long id) throws ResourceNotFoundException {
        EmailConfigEntity entity = emailConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email Config", id));
        return ResponseEntity.ok(toResponse(entity));
    }

    @GetMapping("/active")
    public ResponseEntity<EmailConfigResponse> getActive() {
        EmailConfigEntity entity = emailConfigRepository.findByIsActiveTrue().orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(entity));
    }

    @PostMapping
    public ResponseEntity<EmailConfigResponse> create(@Valid @RequestBody EmailConfigRequest request) {
        // Si se marca como activo, desactivar otros
        if (Boolean.TRUE.equals(request.getIsActive())) {
            emailConfigRepository.findByIsActiveTrue()
                    .ifPresent(existing -> {
                        existing.setIsActive(false);
                        emailConfigRepository.save(existing);
                    });
        }
        
        EmailConfigEntity entity = toEntity(request);
        EmailConfigEntity saved = emailConfigRepository.save(entity);
        return ResponseEntity.created(URI.create("/api/email-config/" + saved.getId())).body(toResponse(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmailConfigResponse> update(@PathVariable Long id, @Valid @RequestBody EmailConfigRequest request)
            throws ResourceNotFoundException {
        EmailConfigEntity entity = emailConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email Config", id));

        // Si se marca como activo, desactivar otros
        if (Boolean.TRUE.equals(request.getIsActive()) && !Boolean.TRUE.equals(entity.getIsActive())) {
            emailConfigRepository.findByIsActiveTrue()
                    .ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            existing.setIsActive(false);
                            emailConfigRepository.save(existing);
                        }
                    });
        }

        updateEntity(entity, request);
        EmailConfigEntity updated = emailConfigRepository.save(entity);
        return ResponseEntity.ok(toResponse(updated));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<String> testConnection(@PathVariable Long id) throws ResourceNotFoundException {
        EmailConfigEntity entity = emailConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email Config", id));
        
        // TODO: Implementar prueba de conexión SMTP
        return ResponseEntity.ok("Test connection functionality to be implemented");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws ResourceNotFoundException {
        if (!emailConfigRepository.existsById(id)) {
            throw new ResourceNotFoundException("Email Config", id);
        }
        emailConfigRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private EmailConfigResponse toResponse(EmailConfigEntity entity) {
        return EmailConfigResponse.builder()
                .id(entity.getId())
                .smtpHost(entity.getSmtpHost())
                .smtpPort(entity.getSmtpPort())
                .username(entity.getUsername())
                .fromEmail(entity.getFromEmail())
                .fromName(entity.getFromName())
                .useTls(entity.getUseTls())
                .useSsl(entity.getUseSsl())
                .isActive(entity.getIsActive())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private EmailConfigEntity toEntity(EmailConfigRequest request) {
        return EmailConfigEntity.builder()
                .smtpHost(request.getSmtpHost())
                .smtpPort(request.getSmtpPort())
                .username(request.getUsername())
                .password(request.getPassword()) // TODO: Encriptar antes de guardar
                .fromEmail(request.getFromEmail())
                .fromName(request.getFromName())
                .useTls(request.getUseTls() != null ? request.getUseTls() : true)
                .useSsl(request.getUseSsl() != null ? request.getUseSsl() : false)
                .isActive(request.getIsActive() != null ? request.getIsActive() : false)
                .description(request.getDescription())
                .build();
    }

    private void updateEntity(EmailConfigEntity entity, EmailConfigRequest request) {
        if (request.getSmtpHost() != null) entity.setSmtpHost(request.getSmtpHost());
        if (request.getSmtpPort() != null) entity.setSmtpPort(request.getSmtpPort());
        if (request.getUsername() != null) entity.setUsername(request.getUsername());
        if (request.getPassword() != null) entity.setPassword(request.getPassword()); // TODO: Encriptar
        if (request.getFromEmail() != null) entity.setFromEmail(request.getFromEmail());
        if (request.getFromName() != null) entity.setFromName(request.getFromName());
        if (request.getUseTls() != null) entity.setUseTls(request.getUseTls());
        if (request.getUseSsl() != null) entity.setUseSsl(request.getUseSsl());
        if (request.getIsActive() != null) entity.setIsActive(request.getIsActive());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
    }
}

