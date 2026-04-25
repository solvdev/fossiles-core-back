package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.infrastructure.service.S3StorageService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final S3StorageService s3StorageService;

    @PostMapping("/images")
    public ResponseEntity<UploadResponse> uploadImage(@RequestPart("file") MultipartFile file) throws IOException {
        S3StorageService.UploadResult result = s3StorageService.uploadImage(file);
        return ResponseEntity.ok(UploadResponse.builder()
                .key(result.getKey())
                .url(result.getUrl())
                .build());
    }

    @PostMapping("/pdf")
    public ResponseEntity<UploadResponse> uploadPDF(@RequestPart("file") MultipartFile file) throws IOException {
        if (!file.getContentType().equals("application/pdf")) {
            return ResponseEntity.badRequest().build();
        }
        S3StorageService.UploadResult result = s3StorageService.uploadPDF(file);
        return ResponseEntity.ok(UploadResponse.builder()
                .key(result.getKey())
                .url(result.getUrl())
                .build());
    }

    @PostMapping("/docx")
    public ResponseEntity<UploadResponse> uploadDOCX(@RequestPart("file") MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || 
            (!contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document") 
             && !contentType.equals("application/msword"))) {
            return ResponseEntity.badRequest().build();
        }
        S3StorageService.UploadResult result = s3StorageService.uploadDOCX(file);
        return ResponseEntity.ok(UploadResponse.builder()
                .key(result.getKey())
                .url(result.getUrl())
                .build());
    }

    @Data
    @Builder
    public static class UploadResponse {
        private String key;
        private String url;
    }
}

