package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoOpeningInventoryItemUpsertRequest {
    private Long productId;
    private Long colorId;
    private Integer quantity;
    /** Cinchos FOSS: cantidad por talla. */
    private Map<String, Integer> sizes;
}
