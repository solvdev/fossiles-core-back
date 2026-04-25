package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialStickerResponse {
    private Long materialId;
    private String sku;
    private String name;
    private String qrData; // ID del material para escanear y ver kardex
}

