package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupervisorKioskAssignmentResponse {
    private Long supervisorUserId;
    private String supervisorName;
    private List<KioskOption> kiosks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioskOption {
        private Long id;
        private String name;
        private String code;
    }
}
