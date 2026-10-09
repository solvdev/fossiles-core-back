package com.fossiles.fossilescorebackend.application.service.customeraccount;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Runs the LF receivables SQL scripts, keeping {@code DO $$ ... $$} blocks intact. */
final class LfMigrationScripts {

    private LfMigrationScripts() {
    }

    static void apply(JdbcTemplate jdbc, String relativePath) throws Exception {
        String sql = Files.readString(Path.of(relativePath));
        for (String statement : split(sql)) {
            jdbc.execute((Connection connection) -> {
                try (Statement command = connection.createStatement()) {
                    if (command.execute(statement)) {
                        try (ResultSet rows = command.getResultSet()) {
                            while (rows != null && rows.next()) {
                                // The phase 1 script ends with a read-only balance query.
                            }
                        }
                    }
                }
                return null;
            });
        }
    }

    static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean dollarQuote = false;
        boolean singleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!singleQuote && c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) == '$') {
                dollarQuote = !dollarQuote;
                current.append("$$");
                i++;
                continue;
            }
            if (!dollarQuote && c == '\'') {
                if (singleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                singleQuote = !singleQuote;
            }
            if (c == ';' && !dollarQuote && !singleQuote) {
                addIfExecutable(statements, current);
                continue;
            }
            current.append(c);
        }
        addIfExecutable(statements, current);
        return statements;
    }

    private static void addIfExecutable(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (statement.isEmpty()) {
            return;
        }
        boolean executable = statement.lines()
                .map(String::trim)
                .anyMatch(line -> !line.isEmpty() && !line.startsWith("--"));
        if (executable) {
            statements.add(statement);
        }
    }
}
