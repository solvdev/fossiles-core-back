package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FelTextEncodingHelperTest {

    @Test
    void repairsUtf8MojibakeForAccentedO() {
        String broken = "CUEROGLAM, SOCIEDAD AN\u00C3\u0093NIMA";
        assertThat(FelTextEncodingHelper.repairFelText(broken)).isEqualTo("CUEROGLAM, SOCIEDAD ANÓNIMA");
    }

    @Test
    void keepsAlreadyCorrectText() {
        String correct = "CUEROGLAM, SOCIEDAD ANÓNIMA";
        assertThat(FelTextEncodingHelper.repairFelText(correct)).isEqualTo(correct);
    }
}
