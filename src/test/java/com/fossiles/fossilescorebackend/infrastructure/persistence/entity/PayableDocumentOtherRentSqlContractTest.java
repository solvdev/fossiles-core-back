package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads {@code scripts/migration-payable-rent-other.sql} (never executes it) and checks that
 * {@link PayableDocumentEntity#otherRentId} matches the added column: name, type, nullability, FK.
 */
class PayableDocumentOtherRentSqlContractTest {

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ADD COLUMN IF NOT EXISTS other_rent_id\\s+(BIGINT)((?:\\s+(?!REFERENCES)\\S+)*)?\\s+"
                    + "REFERENCES\\s+([a-z_]+)\\s*\\(\\s*([a-z_]+)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    void otherRentIdMatchesTheSqlScript() throws Exception {
        Path sqlPath = Path.of("scripts/migration-payable-rent-other.sql");
        assertTrue(Files.isRegularFile(sqlPath), "missing " + sqlPath.toAbsolutePath());
        String sql = Files.readString(sqlPath);
        String live = sql.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")).strip() : line)
                .collect(Collectors.joining("\n"));
        assertTrue(live.toUpperCase(Locale.ROOT).contains("ROLLBACK;"),
                "the review copy of the script must stay on ROLLBACK");
        assertFalse(live.toUpperCase(Locale.ROOT).contains("COMMIT;"),
                "the review copy of the script must stay on ROLLBACK");

        Matcher matcher = ADD_COLUMN.matcher(sql);
        assertTrue(matcher.find(), "ADD COLUMN other_rent_id BIGINT REFERENCES other_rent (id) not found");
        assertEquals("BIGINT", matcher.group(1).toUpperCase(Locale.ROOT));
        String extras = matcher.group(2) == null ? "" : matcher.group(2).toUpperCase(Locale.ROOT);
        assertFalse(extras.contains("NOT NULL"), "other_rent_id must be nullable");
        assertEquals("other_rent", matcher.group(3).toLowerCase(Locale.ROOT));
        assertEquals("id", matcher.group(4).toLowerCase(Locale.ROOT));

        Field field = PayableDocumentEntity.class.getDeclaredField("otherRentId");
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals("other_rent_id", column.name());
        assertEquals(Long.class, field.getType());
        assertTrue(column.nullable());
        assertNull(field.getAnnotation(JoinColumn.class));
        assertNull(field.getAnnotation(ManyToOne.class));
    }
}
