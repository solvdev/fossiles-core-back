package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Descuento de colaborador por producto. */
@Data
public class ProductEmployeeDiscountRequest {
    @NotEmpty
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull
        private Long productId;

        /** De 0 a 100. Vacío: el producto vuelve al descuento general (50 %). */
        private BigDecimal discountPercent;
    }
}
