package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Opciones del filtro de kiosco en «Envíos», ya limitadas a lo que el usuario puede ver. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentSearchOptionsResponse {
    /** False para una encargada: solo ve los envíos de sus kioscos. */
    private boolean allKiosks;
    private List<Kiosk> kiosks;
    private List<Supervisor> supervisors;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Kiosk {
        private Long id;
        private String code;
        private String name;
        private String departamento;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Supervisor {
        private Long userId;
        private String name;
        private List<Long> kioskIds;
    }
}
