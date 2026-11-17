package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SupplierPaymentPayeeKeyTest {

    @Test
    void aSupplierIsIdentifiedByItsId() {
        assertEquals("S:7", SupplierPaymentService.payeeKeyOf(7L, "Cualquier nombre"));
    }

    @Test
    void aNameWithoutSupplierIgnoresAccentsCaseAndExtraSpaces() {
        assertEquals(SupplierPaymentService.payeeKeyOf(null, "  Inmobiliaria  Ejemplo, S.A. "),
                SupplierPaymentService.payeeKeyOf(null, "INMOBILIARIA EJEMPLO, S.A."));
        assertEquals(SupplierPaymentService.payeeKeyOf(null, "Inversiones Peña"),
                SupplierPaymentService.payeeKeyOf(null, "inversiones pena"));
        assertNotEquals(SupplierPaymentService.payeeKeyOf(null, "Plaza Uno"), SupplierPaymentService.payeeKeyOf(null, "Plaza Dos"));
    }
}
