package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaquilaDispatchResponse {
    private LocalDate date;
    private Integer desk;
    private String deskName;
    private int totalQuantity;
    private double totalHours;
    private List<Created> created;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Created {
        private Long taskId;
        private String code;
        private String productionOrderCode;
        private int quantity;
        private double hours;
    }
}
