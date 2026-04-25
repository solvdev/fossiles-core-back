package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrintFormatResponse {
    private Long id;
    private String documentType;
    private String formatName;
    private String templatePath;
    private String paperSize;
    private String margins;
    private String header;
    private String footer;
    private String logoPath;
    private Boolean isDefault;
    private String description;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

