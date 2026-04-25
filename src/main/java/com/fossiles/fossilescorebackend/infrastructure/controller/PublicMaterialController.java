package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UomRepository;
import com.fossiles.fossilescorebackend.infrastructure.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/public/materials")
@RequiredArgsConstructor
public class PublicMaterialController {

    private final MaterialRepository materialRepository;
    private final UomRepository uomRepository;
    private final S3StorageService s3StorageService;

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBasicMaterial(@PathVariable Long id) throws ResourceNotFoundException {
        MaterialEntity entity = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));
        UomEntity purchaseUom = entity.getPurchaseUomId() != null
                ? uomRepository.findById(entity.getPurchaseUomId()).orElse(null)
                : null;
        UomEntity manufacturingUom = entity.getManufacturingUomId() != null
                ? uomRepository.findById(entity.getManufacturingUomId()).orElse(null)
                : null;
        String conversionText = null;
        if (entity.getPurchaseQuantity() != null && purchaseUom != null && manufacturingUom != null) {
            conversionText = "1 " + purchaseUom.getName() + " = "
                    + entity.getPurchaseQuantity().stripTrailingZeros().toPlainString()
                    + " " + manufacturingUom.getName();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", entity.getId());
        response.put("sku", entity.getSku());
        response.put("name", entity.getName());
        response.put("imageUrl", buildPublicImageUrl(entity));
        response.put("status", entity.getStatus());
        response.put("uomId", entity.getUomId());
        response.put("purchaseUomId", entity.getPurchaseUomId());
        response.put("manufacturingUomId", entity.getManufacturingUomId());
        response.put("purchaseQuantity", entity.getPurchaseQuantity());
        response.put("purchaseUomCode", purchaseUom != null ? purchaseUom.getCode() : null);
        response.put("purchaseUomName", purchaseUom != null ? purchaseUom.getName() : null);
        response.put("manufacturingUomCode", manufacturingUom != null ? manufacturingUom.getCode() : null);
        response.put("manufacturingUomName", manufacturingUom != null ? manufacturingUom.getName() : null);
        response.put("conversionText", conversionText);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchMaterials(@RequestParam("query") String query) {
        List<MaterialEntity> materials = materialRepository.searchBySkuOrName(query);
        List<Map<String, Object>> response = new ArrayList<>();

        for (MaterialEntity material : materials) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", material.getId());
            item.put("sku", material.getSku());
            item.put("name", material.getName());
            item.put("imageUrl", buildPublicImageUrl(material));
            item.put("status", material.getStatus());
            response.add(item);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getMaterialImage(@PathVariable Long id) throws ResourceNotFoundException, IOException {
        MaterialEntity entity = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));

        String url = entity.getImageUrl();
        if (url == null || url.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new byte[0]);
        }

        S3StorageService.DownloadResult file = s3StorageService.downloadByUrl(url);
        String contentType = file.getContentType();
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (contentType != null && !contentType.trim().isEmpty()) {
            try {
                mediaType = MediaType.parseMediaType(contentType);
            } catch (Exception ignored) {}
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.getBytes() != null ? file.getBytes().length : 0))
                .body(file.getBytes() != null ? file.getBytes() : new byte[0]);
    }

    @PostMapping("/{id}/image")
    public ResponseEntity<Map<String, Object>> uploadMaterialImage(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file)
            throws ResourceNotFoundException, IOException {

        MaterialEntity entity = materialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Debe seleccionar una imagen");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("El archivo debe ser una imagen");
        }

        S3StorageService.UploadResult uploadResult = s3StorageService.uploadImage(file);
        entity.setImageUrl(uploadResult.getUrl());
        MaterialEntity saved = materialRepository.save(entity);

        Map<String, Object> response = new HashMap<>();
        response.put("id", saved.getId());
        response.put("sku", saved.getSku());
        response.put("name", saved.getName());
        response.put("imageUrl", buildPublicImageUrl(saved));
        response.put("status", saved.getStatus());

        return ResponseEntity.ok(response);
    }

    private String buildPublicImageUrl(MaterialEntity entity) {
        if (entity == null || entity.getId() == null) return "";
        String raw = entity.getImageUrl();
        if (raw == null || raw.trim().isEmpty()) return "";
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/public/materials/")
                .path(String.valueOf(entity.getId()))
                .path("/image")
                .toUriString();
    }
}
