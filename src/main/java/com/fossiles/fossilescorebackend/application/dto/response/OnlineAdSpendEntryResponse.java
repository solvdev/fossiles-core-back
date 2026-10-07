package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Inversión en publicidad capturada para un día. {@code updatedBy} es el nombre de quien la modificó por última vez. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineAdSpendEntryResponse {
    private LocalDate date;
    private BigDecimal amount;
    private String notes;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
