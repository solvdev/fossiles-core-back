package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCinchoAudienceTest {

    @Test
    void normalize_mapsNinoNinaWithAccents() {
        assertThat(ProductCinchoAudience.normalize("niño")).isEqualTo("NINO");
        assertThat(ProductCinchoAudience.normalize("NINA")).isEqualTo("NINA");
        assertThat(ProductCinchoAudience.normalize("Niña")).isEqualTo("NINA");
        assertThat(ProductCinchoAudience.normalize("LEVIS")).isNull();
        assertThat(ProductCinchoAudience.normalize("NUEVO")).isNull();
    }

    @Test
    void label_spanish() {
        assertThat(ProductCinchoAudience.label("NINO")).isEqualTo("Niño");
        assertThat(ProductCinchoAudience.label("niña")).isEqualTo("Niña");
    }
}
