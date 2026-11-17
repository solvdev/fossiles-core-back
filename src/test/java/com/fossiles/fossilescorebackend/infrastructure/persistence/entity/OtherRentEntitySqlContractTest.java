package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads {@code scripts/migration-other-rent.sql} (never executes it) and checks that
 * {@link OtherRentEntity} has the same columns and types so {@code ddl-auto=validate} can pass.
 */
class OtherRentEntitySqlContractTest {

    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*([a-z_]+)\\s+([A-Z]+)(?:\\(([^)]+)\\))?(.*)$");

    @Test
    void entityColumnsMatchTheSqlScript() throws Exception {
        Path sqlPath = Path.of("scripts/migration-other-rent.sql");
        assertTrue(Files.isRegularFile(sqlPath), "missing " + sqlPath.toAbsolutePath());
        String sql = Files.readString(sqlPath);
        assertFalse(sql.toLowerCase(Locale.ROOT).contains("commit;"),
                "the review copy of the script must stay on ROLLBACK");

        Map<String, SqlColumn> sqlColumns = parseCreateTable(sql);
        Table table = OtherRentEntity.class.getAnnotation(Table.class);
        assertEquals("other_rent", table.name());
        assertEquals(0, table.indexes().length);
        assertEquals(0, table.uniqueConstraints().length);
        assertTrue(sql.contains("uq_other_rent_active_category_name"),
                "SQL must keep the partial unique index the entity does not declare");

        Map<String, JpaColumn> jpaColumns = jpaColumns();
        assertEquals(sqlColumns.keySet(), jpaColumns.keySet());
        for (String name : sqlColumns.keySet()) {
            SqlColumn sqlColumn = sqlColumns.get(name);
            JpaColumn jpaColumn = jpaColumns.get(name);
            assertEquals(sqlColumn.type(), jpaColumn.type(), name);
            assertEquals(sqlColumn.notNull(), jpaColumn.notNull(), name + " nullability");
        }
    }

    private static Map<String, SqlColumn> parseCreateTable(String sql) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS other_rent (");
        int end = sql.indexOf(");", start);
        assertTrue(start >= 0 && end > start, "CREATE TABLE other_rent not found");
        String body = sql.substring(sql.indexOf('(', start) + 1, end);
        Map<String, SqlColumn> columns = new LinkedHashMap<>();
        for (String rawLine : body.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("CONSTRAINT") || line.startsWith("--")) {
                continue;
            }
            if (line.endsWith(",")) {
                line = line.substring(0, line.length() - 1).strip();
            }
            Matcher matcher = COLUMN.matcher(line);
            assertTrue(matcher.matches(), "unreadable column line: " + rawLine);
            String name = matcher.group(1);
            String type = matcher.group(2);
            String args = matcher.group(3);
            String rest = matcher.group(4);
            if (args != null) {
                type = type + "(" + args.replace(" ", "") + ")";
            }
            boolean notNull = rest.contains("NOT NULL") || rest.contains("PRIMARY KEY");
            columns.put(name, new SqlColumn(type, notNull));
        }
        return columns;
    }

    private static Map<String, JpaColumn> jpaColumns() {
        Map<String, JpaColumn> columns = new LinkedHashMap<>();
        for (Field field : OtherRentEntity.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            String name = column != null && !column.name().isEmpty() ? column.name() : field.getName();
            boolean identity = field.getAnnotation(Id.class) != null
                    && field.getAnnotation(GeneratedValue.class) != null
                    && field.getAnnotation(GeneratedValue.class).strategy() == GenerationType.IDENTITY;
            boolean notNull = field.getType().isPrimitive()
                    || field.getAnnotation(Id.class) != null
                    || (column != null && !column.nullable());
            columns.put(name, new JpaColumn(sqlType(field, column, identity), notNull));
        }
        return columns;
    }

    private static String sqlType(Field field, Column column, boolean identity) {
        Class<?> type = field.getType();
        if (identity && type == Long.class) {
            return "BIGSERIAL";
        }
        if (type == Long.class) {
            return "BIGINT";
        }
        if (type == Integer.class) {
            return "INT";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "BOOLEAN";
        }
        if (type == String.class) {
            int length = column != null ? column.length() : 255;
            return "VARCHAR(" + length + ")";
        }
        if (type == BigDecimal.class) {
            assertNotNull(column);
            return "NUMERIC(" + column.precision() + "," + column.scale() + ")";
        }
        if (type == LocalDate.class) {
            return "DATE";
        }
        if (type == LocalDateTime.class) {
            return "TIMESTAMP";
        }
        throw new IllegalStateException("no SQL mapping for " + field.getName() + " " + type.getName());
    }

    private record SqlColumn(String type, boolean notNull) {
    }

    private record JpaColumn(String type, boolean notNull) {
    }
}
