package com.fossiles.fossilescorebackend.application.service.customeraccount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * source-commit: 2de414f5e8b42ae03d046a3854218f68f396b87b
 *
 * The classpath phase 1 file is a byte-identical copy of
 * scripts/migration-customer-account-lf-phase1.sql on cursor/lf-receivables-ledger-69a5.
 * Skipped when that ref is not in the checkout.
 */
class Phase1ScriptCopyTest {

    private static final String LEDGER_SCRIPT =
            "origin/cursor/lf-receivables-ledger-69a5:scripts/migration-customer-account-lf-phase1.sql";
    private static final Path COPY = Path.of("src/test/resources/db/migration-customer-account-lf-phase1.sql");

    @Test
    void copyMatchesLedgerHeadWhenTheRefIsPresent() throws Exception {
        Assumptions.assumeTrue(refAvailable(),
                "origin/cursor/lf-receivables-ledger-69a5 is not in this checkout");

        assertThat(Files.readAllBytes(COPY)).isEqualTo(gitShow(LEDGER_SCRIPT));
    }

    private static boolean refAvailable() throws Exception {
        Process process = new ProcessBuilder("git", "cat-file", "-e", LEDGER_SCRIPT)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return process.waitFor() == 0;
    }

    private static byte[] gitShow(String object) throws Exception {
        Process process = new ProcessBuilder("git", "show", object)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        byte[] bytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git show " + object + " exited " + exit);
        }
        return bytes;
    }
}
