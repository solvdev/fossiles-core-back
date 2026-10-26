package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosHeldCartResponse {
    private Long id;
    private Long kioskLocationId;
    private String label;
    private String cartJson;
    private LocalDate heldDate;
    private Long createdByUserId;
    private String createdByName;
    private LocalDateTime createdAt;
}
