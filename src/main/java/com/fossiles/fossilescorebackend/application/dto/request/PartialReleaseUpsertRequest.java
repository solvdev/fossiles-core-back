package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartialReleaseUpsertRequest {
    private String label;
    private String notes;
    /** DRAFT o CONFIRMED al guardar. */
    private String status;
    private List<PartialReleaseLineRequest> lines;
}
