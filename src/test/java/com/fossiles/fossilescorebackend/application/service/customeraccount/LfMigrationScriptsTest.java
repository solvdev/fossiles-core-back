package com.fossiles.fossilescorebackend.application.service.customeraccount;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LfMigrationScriptsTest {

    @Test
    void ledgerScriptsStopOnTheFirstPsqlError() throws Exception {
        for (String name : new String[] {
                "migration-customer-account-lf-phase1.sql",
                "migration-customer-account-lf-phase2.sql",
                "rollback-customer-account-lf-phase1.sql",
                "rollback-customer-account-lf-phase2.sql"
        }) {
            String sql = Files.readString(Path.of("scripts", name));
            assertThat(sql).contains("\\set ON_ERROR_STOP on");
        }
        assertThat(Files.readString(Path.of("scripts/migration-customer-account-lf-phase2.sql")))
                .contains("FASE 2 abortada")
                .contains("No se cambio nada");
        assertThat(Files.readString(Path.of("scripts/rollback-customer-account-lf-phase1.sql")))
                .contains("ROLLBACK FASE 1 abortado")
                .contains("CHARGE_ADJUSTMENT");
    }
}
