package com.fossiles.fossilescorebackend.application.service.customeraccount;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The old void guidance must not remain in production source. */
class LegacyReregisterPhraseAbsenceTest {

    @Test
    void reregisterPhraseIsAbsentFromMainSources() throws Exception {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if ("application.properties".equals(name)) {
                    return;
                }
                try {
                    String text = Files.readString(path);
                    if (text.contains("Anúlelo") || text.contains("registrarlo de nuevo")) {
                        hits.add(path.toString());
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException(path.toString(), ex);
                }
            });
        }
        assertThat(hits).isEmpty();
    }
}
