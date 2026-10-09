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
                    if (containsOldVoidPhrase(text)) {
                        hits.add(path.toString());
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException(path.toString(), ex);
                }
            });
        }
        assertThat(hits).isEmpty();
    }

    /** "Anúlelos primero" is the new shipping-adjustment instruction. The old singular phrase is not. */
    private static boolean containsOldVoidPhrase(String text) {
        if (text.contains("registrarlo de nuevo")) {
            return true;
        }
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf("Anúlelo", from);
            if (at < 0) {
                return false;
            }
            int after = at + "Anúlelo".length();
            if (after >= text.length() || text.charAt(after) != 's') {
                return true;
            }
            from = after;
        }
        return false;
    }
}
