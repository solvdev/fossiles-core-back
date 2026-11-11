package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mesa especial (maquila): número fijo, nombre y si está activa. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecialDeskResponse {
    private Long id;
    private Integer desk;
    private String name;
    private String kind;
    private boolean active;
}
