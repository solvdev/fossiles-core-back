package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCinchoAudienceTest {

    @Test
    void normalize_mapsNinoNinaWithAccents() {
        assertThat(ProductCinchoAudience.normalize("niño")).isEqualTo("NINO");
        assertThat(ProductCinchoAudience.normalize("NINA")).isEqualTo("DAMA");
        assertThat(ProductCinchoAudience.normalize("Niña")).isEqualTo("DAMA");
        assertThat(ProductCinchoAudience.normalize("Dama")).isEqualTo("DAMA");
        assertThat(ProductCinchoAudience.normalize("LEVIS")).isNull();
        assertThat(ProductCinchoAudience.normalize("NUEVO")).isNull();
    }

    @Test
    void label_spanish() {
        assertThat(ProductCinchoAudience.label("NINO")).isEqualTo("Niño");
        assertThat(ProductCinchoAudience.label("niña")).isEqualTo("Dama");
        assertThat(ProductCinchoAudience.label("dama")).isEqualTo("Dama");
    }

    @Test
    void fromSize_splitsNinoAndDamaAt30() {
        assertThat(ProductCinchoAudience.fromSize("16")).isEqualTo("NINO");
        assertThat(ProductCinchoAudience.fromSize("28")).isEqualTo("NINO");
        assertThat(ProductCinchoAudience.fromSize("30")).isEqualTo("DAMA");
        assertThat(ProductCinchoAudience.fromSize("32")).isEqualTo("DAMA");
        assertThat(ProductCinchoAudience.fromSize(null)).isNull();
        assertThat(ProductCinchoAudience.fromSize("NUEVO")).isNull();
    }
}
