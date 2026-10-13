package com.fossiles.fossilescorebackend.application.service.customeraccount;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * Runs the LF ledger scripts with {@code psql -v ON_ERROR_STOP=1 -f} inside the
 * PostgreSQL container. The Index scripts are psql programs ({@code \set}, {@code \gset},
 * {@code \if}, {@code \copy}, {@code CREATE INDEX CONCURRENTLY}); a JDBC splitter would
 * not keep their transactions or stop on the first error.
 */
final class LfMigrationScripts {

    private LfMigrationScripts() {
    }

    static void apply(PostgreSQLContainer<?> postgres, String relativePath) throws Exception {
        PsqlResult result = run(postgres, relativePath);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "psql " + relativePath + " exited " + result.exitCode() + ": " + result.output());
        }
    }

    static PsqlResult run(PostgreSQLContainer<?> postgres, String relativePath) throws Exception {
        String remote = "/tmp/" + Path.of(relativePath).getFileName();
        postgres.copyFileToContainer(
                MountableFile.forHostPath(Path.of(relativePath).toAbsolutePath()), remote);
        String command = "cd /tmp && PGPASSWORD=" + shellQuote(postgres.getPassword())
                + " psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -U " + shellQuote(postgres.getUsername())
                + " -d " + shellQuote(postgres.getDatabaseName())
                + " -f " + shellQuote(remote);
        Container.ExecResult result = postgres.execInContainer("sh", "-c", command);
        return new PsqlResult(result.getExitCode(), result.getStdout() + result.getStderr());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    record PsqlResult(int exitCode, String output) {
    }
}
