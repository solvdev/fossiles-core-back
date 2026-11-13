package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CinchoSizeKeysTest {

    @Test
    void normalizeTrimsUppercasesAndKeepsUnusualSizes() {
        assertThat(CinchoSizeKeys.normalize(" 32 ")).isEqualTo("32");
        assertThat(CinchoSizeKeys.normalize("xl")).isEqualTo("XL");
        assertThat(CinchoSizeKeys.normalize("95")).isEqualTo("95");
        assertThat(CinchoSizeKeys.normalize("  ")).isEmpty();
        assertThat(CinchoSizeKeys.normalize(null)).isEmpty();
    }

    @Test
    void aggregateSumsRepeatedKeysAndSkipsBlankOrNonPositive() {
        Map<String, Integer> raw = new LinkedHashMap<>();
        raw.put(" 32 ", 1);
        raw.put("32", 2);
        raw.put("xl", 1);
        raw.put("  ", 4);
        raw.put("95", 0);
        raw.put("m", null);

        Map<String, Integer> aggregated = CinchoSizeKeys.aggregate(raw);

        assertThat(aggregated).containsExactly(
                Map.entry("32", 3),
                Map.entry("XL", 1));
        assertThat(CinchoSizeKeys.sum(aggregated)).isEqualTo(4);
        assertThat(CinchoSizeKeys.toJson(aggregated)).isEqualTo("{\"32\":3,\"XL\":1}");
        assertThat(CinchoSizeKeys.toJson(Map.of())).isNull();
        assertThat(CinchoSizeKeys.aggregate(null)).isEmpty();
    }
}
