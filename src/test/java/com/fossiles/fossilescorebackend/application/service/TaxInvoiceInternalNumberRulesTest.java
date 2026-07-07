package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class TaxInvoiceInternalNumberRulesTest {

    @Test
    void needsAssignmentForMissingPlaceholderOrWrongSeries() throws Exception {
        Method method = TaxInvoiceService.class.getDeclaredMethod(
                "needsInternalNumberAssignment",
                String.class,
                String.class
        );
        method.setAccessible(true);

        assertThat(method.invoke(null, null, "A45")).isEqualTo(true);
        assertThat(method.invoke(null, "TINV-000042", "A45")).isEqualTo(true);
        assertThat(method.invoke(null, "B-12", "A45")).isEqualTo(true);
        assertThat(method.invoke(null, "VL20260101-0005", "A45")).isEqualTo(true);
        assertThat(method.invoke(null, "A45-28", "A45")).isEqualTo(false);
        assertThat(method.invoke(null, "A45-14", "A45")).isEqualTo(false);
    }
}
