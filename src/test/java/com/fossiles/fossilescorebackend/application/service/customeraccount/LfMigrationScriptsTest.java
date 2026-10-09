package com.fossiles.fossilescorebackend.application.service.customeraccount;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LfMigrationScriptsTest {

    @Test
    void commentSemicolonIsNotAStatementBoundary() throws Exception {
        String sql = Files.readString(Path.of("scripts/migration-customer-account-lf-phase1.sql"));
        assertThat(LfMigrationScripts.split(sql))
                .isNotEmpty()
                .allSatisfy(statement -> assertThat(statement.stripLeading()).doesNotStartWith("totals"))
                .anyMatch(statement -> statement.contains("CHARGE_ADJUSTMENT"));
    }
}
