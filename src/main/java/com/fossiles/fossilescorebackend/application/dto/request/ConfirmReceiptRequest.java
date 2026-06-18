package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmReceiptRequest {
    private String notes;

    @Valid
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long detailId;
        private java.math.BigDecimal quantityReceived;
        private String lineNotes;
    }
}
