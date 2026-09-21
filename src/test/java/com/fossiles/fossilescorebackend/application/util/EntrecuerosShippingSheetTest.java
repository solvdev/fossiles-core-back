package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntrecuerosShippingSheetTest {

    @Test
    void withoutInvoice_usesSheetOnly() {
        assertThat(EntrecuerosShippingSheet.composeInternalNumber(null, "  1842 "))
                .isEqualTo("1842");
        assertThat(EntrecuerosShippingSheet.composeInternalNumber("  ", "1842"))
                .isEqualTo("1842");
    }

    @Test
    void withInvoice_appendsSheet() {
        assertThat(EntrecuerosShippingSheet.composeInternalNumber("A45-241", "1842"))
                .isEqualTo("A45-241 - 1842");
    }

    @Test
    void withoutSheet_keepsInvoice() {
        assertThat(EntrecuerosShippingSheet.composeInternalNumber("A45-241", null))
                .isEqualTo("A45-241");
        assertThat(EntrecuerosShippingSheet.composeInternalNumber(null, "  "))
                .isNull();
    }

    @Test
    void alreadyComposed_doesNotDuplicateSheet() {
        assertThat(EntrecuerosShippingSheet.composeInternalNumber("A45-241 - 1842", "1842"))
                .isEqualTo("A45-241 - 1842");
    }
}
