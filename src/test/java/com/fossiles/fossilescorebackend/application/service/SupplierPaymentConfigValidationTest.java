package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ChequeLayoutDto;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.LayoutField;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupplierPaymentConfigValidationTest {

    private static ChequeLayoutDto layout(String font, LayoutField... fields) {
        return new ChequeLayoutDto(null, "Chequera", 165, 70, font, List.of(fields), true);
    }

    @Test
    void acceptsTheFormatOfThePayrollCheque() {
        assertDoesNotThrow(() -> SupplierPaymentConfigService.validate(layout("TIMES",
                new LayoutField("DATE", true, 17, "LEFT", 20, 10),
                new LayoutField("AMOUNT", true, 25.7, "RIGHT", 10, 10),
                new LayoutField("PAYEE", true, 26.4, "LEFT", 19.4, 11),
                new LayoutField("WORDS", true, 30.5, "LEFT", 18.3, 9))));
    }

    @Test
    void rejectsFieldsOutsideThePaperRepeatedKeysAndUnknownFonts() {
        assertThrows(BusinessException.class, () -> SupplierPaymentConfigService.validate(layout("TIMES",
                new LayoutField("DATE", true, 80, "LEFT", 20, 10))));
        assertThrows(BusinessException.class, () -> SupplierPaymentConfigService.validate(layout("TIMES",
                new LayoutField("DATE", true, 17, "LEFT", 20, 10),
                new LayoutField("DATE", true, 20, "LEFT", 20, 10))));
        assertThrows(BusinessException.class, () -> SupplierPaymentConfigService.validate(layout("COMIC",
                new LayoutField("DATE", true, 17, "LEFT", 20, 10))));
        assertThrows(BusinessException.class, () -> SupplierPaymentConfigService.validate(layout("TIMES",
                new LayoutField("DATE", true, 17, "CENTER", 20, 10))));
    }
}
