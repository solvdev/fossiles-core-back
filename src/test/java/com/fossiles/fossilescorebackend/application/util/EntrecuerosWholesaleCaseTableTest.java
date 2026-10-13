package com.fossiles.fossilescorebackend.application.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reviewed Entrecueros wholesale case table (pricing and kind classification).
 * Sale and estimate both recompute a line with
 * {@link EntrecuerosPriceLists#addVolumeQuantity},
 * {@link EntrecuerosPriceLists#resolveChargedUnitPrice} and
 * {@link EntrecuerosPriceLists#lineTotal}; the client unit price is ignored.
 */
class EntrecuerosWholesaleCaseTableTest {

    static final String CASES_RESOURCE = "/pricing/entrecueros-wholesale-cases.json";
    static final String SHA256_RESOURCE = "/pricing/entrecueros-wholesale-cases.json.sha256";
    static final String EXPECTED_SHA256 =
            "6e4a105cd91b6b395f4596dcdfee9853b3ada66e71bd7161dd7ca43a37397396";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static JsonNode root;

    @Test
    void caseTableSha256MatchesReviewedHash() throws Exception {
        byte[] json = readResource(CASES_RESOURCE);
        String actual = sha256(json);
        assertThat(actual)
                .as("sha256 of " + CASES_RESOURCE)
                .isEqualTo(EXPECTED_SHA256);

        String recorded = new String(readResource(SHA256_RESOURCE), StandardCharsets.UTF_8)
                .trim()
                .split("\\s+", 2)[0];
        assertThat(recorded)
                .as("sha256 sidecar")
                .isEqualTo(EXPECTED_SHA256);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pricingCaseIds")
    void recomputesSaleAndEstimatePrices(String id) throws IOException {
        JsonNode item = find(root().get("cases"), id);
        String skip = skipReason(item);
        assumeTrue(skip == null, skip);

        List<Line> lines = linesOf(item.get("lines"));
        Map<String, BigDecimal> qtyByVolumeKey = new HashMap<>();
        for (Line line : lines) {
            EntrecuerosPriceLists.addVolumeQuantity(
                    qtyByVolumeKey,
                    line.product().getId(),
                    line.product(),
                    line.hardware(),
                    line.quantity());
        }

        JsonNode expected = item.get("expected");
        assertThat(expected).as(id + " expected").isNotNull();
        JsonNode expectedLines = expected.get("lines");
        assertThat(expectedLines).as(id + " lines").hasSize(lines.size());

        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            BigDecimal unitPrice = EntrecuerosPriceLists.resolveChargedUnitPrice(
                    line.product(), line.hardware(), line.quantity(), qtyByVolumeKey);
            BigDecimal lineTotal = EntrecuerosPriceLists.lineTotal(unitPrice, line.quantity());
            JsonNode expectedLine = expectedLines.get(i);
            assertThat(unitPrice)
                    .as(id + " line " + (i + 1) + " unitPrice")
                    .isEqualByComparingTo(money(expectedLine.get("unitPrice")));
            assertThat(lineTotal)
                    .as(id + " line " + (i + 1) + " lineTotal")
                    .isEqualByComparingTo(money(expectedLine.get("lineTotal")));
            total = total.add(lineTotal);
        }
        assertThat(total)
                .as(id + " total")
                .isEqualByComparingTo(money(expected.get("total")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("classificationCaseIds")
    void classifiesKind(String id) throws IOException {
        JsonNode item = find(root().get("classification"), id);
        String skip = skipReason(item);
        assumeTrue(skip == null, skip);

        JsonNode input = item.get("input");
        ProductEntity product = ProductEntity.builder()
                .id(input.get("productId").asLong())
                .code(text(input, "code"))
                .name(text(input, "name"))
                .cinchoType(text(input, "cinchoType"))
                .cinchoForKids(input.path("cinchoForKids").asBoolean(false))
                .build();
        EntrecuerosPriceLists.Kind kind = EntrecuerosPriceLists.kind(product, text(input, "hardware"));
        assertThat(caseKind(kind))
                .as(id)
                .isEqualTo(item.get("expectedKind").asText());
    }

    static Stream<String> pricingCaseIds() throws IOException {
        return ids(root().get("cases"));
    }

    static Stream<String> classificationCaseIds() throws IOException {
        return ids(root().get("classification"));
    }

    private static String skipReason(JsonNode item) {
        boolean pending = "pending".equals(item.path("status").asText());
        boolean packagingRejected = hasTag(item, "packaging-rejected");
        if (!pending && !packagingRejected) {
            return null;
        }
        StringBuilder reason = new StringBuilder();
        if (pending) {
            reason.append("status pending");
        }
        if (packagingRejected) {
            if (!reason.isEmpty()) {
                reason.append("; ");
            }
            reason.append("tag packaging-rejected");
        }
        String note = item.path("note").asText("").trim();
        if (!note.isEmpty()) {
            reason.append(": ").append(note);
        }
        return reason.toString();
    }

    private static boolean hasTag(JsonNode item, String tag) {
        for (JsonNode node : item.path("tags")) {
            if (tag.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<Line> linesOf(JsonNode lines) {
        Map<String, ProductEntity> products = new HashMap<>();
        Map<String, Long> ids = new HashMap<>();
        List<Line> prepared = new ArrayList<>();
        for (JsonNode line : lines) {
            String type = line.get("type").asText();
            String sku = line.get("sku_hint").asText();
            String key = type + "\0" + sku;
            long id = ids.computeIfAbsent(key, ignored -> (long) ids.size() + 1);
            ProductEntity product = products.computeIfAbsent(key, ignored -> product(id, type, sku, line));
            prepared.add(new Line(product, hardware(type), BigDecimal.valueOf(line.get("quantity").asLong())));
        }
        return prepared;
    }

    private static ProductEntity product(long id, String type, String sku, JsonNode line) {
        ProductEntity.ProductEntityBuilder builder = ProductEntity.builder()
                .id(id)
                .code(code(type, sku, id))
                .name(name(type));
        switch (type) {
            case "casual", "nino", "dama" -> builder.cinchoType("CASUAL");
            case "reversible" -> builder.cinchoType("REVERSIBLE");
            default -> {
            }
        }
        if (line.hasNonNull("catalogPrice")) {
            builder.salePrice(money(line.get("catalogPrice")));
        }
        JsonNode tiers = line.get("configuredTiers");
        if (tiers != null && !tiers.isNull()) {
            if (tiers.hasNonNull("1")) {
                builder.entrecuerosPriceUnit(money(tiers.get("1")));
            }
            if (tiers.hasNonNull("3")) {
                builder.entrecuerosPriceQty3(money(tiers.get("3")));
            }
            if (tiers.hasNonNull("6")) {
                builder.entrecuerosPriceQty6(money(tiers.get("6")));
            }
            if (tiers.hasNonNull("12")) {
                builder.entrecuerosPriceQty12(money(tiers.get("12")));
            }
        }
        return builder.build();
    }

    private static String code(String type, String sku, long id) {
        if ("sintetica".equals(type) && !"generic".equals(sku)) {
            return sku;
        }
        if ("packaging".equals(type) || "untyped".equals(type)) {
            return sku;
        }
        return "P-" + id;
    }

    private static String name(String type) {
        return switch (type) {
            case "casual", "nino", "dama", "reversible" -> "Cincho";
            case "billetera", "sintetica" -> "Billetera";
            case "tarjetero" -> "Tarjetero";
            case "packaging" -> "Bolsa";
            default -> "Accesorio";
        };
    }

    private static String hardware(String type) {
        return switch (type) {
            case "nino" -> "NINO";
            case "dama" -> "DAMA";
            case "sintetica" -> "SINTETICO";
            default -> "NUEVO";
        };
    }

    private static String caseKind(EntrecuerosPriceLists.Kind kind) {
        return switch (kind) {
            case CASUAL -> "casual";
            case REVERSIBLE -> "reversible";
            case NINO -> "nino";
            case DAMA -> "dama";
            case WALLET_LEATHER -> "billetera";
            case WALLET_SYNTHETIC -> "sintetica";
            case CARDHOLDER_SYNTHETIC -> "tarjetero";
            case PACKAGING -> "packaging";
            case PRODUCT -> "untyped";
        };
    }

    private static JsonNode root() throws IOException {
        if (root == null) {
            root = MAPPER.readTree(readResource(CASES_RESOURCE));
        }
        return root;
    }

    private static Stream<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : array) {
            ids.add(node.get("id").asText());
        }
        return ids.stream();
    }

    private static JsonNode find(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.get("id").asText())) {
                return node;
            }
        }
        throw new IllegalArgumentException("Missing case " + id);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static BigDecimal money(JsonNode node) {
        return node.decimalValue();
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = EntrecuerosWholesaleCaseTableTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Line(ProductEntity product, String hardware, BigDecimal quantity) {
    }
}
