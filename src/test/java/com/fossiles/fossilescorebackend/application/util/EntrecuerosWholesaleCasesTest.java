package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance cases for Entrecueros wholesale.
 * When QA publishes {@code pricing/entrecueros-wholesale-cases.json}
 * (version + sha256), replace {@link #loadCases()} with a parse of that file.
 * The record fields match the file's case objects.
 */
class EntrecuerosWholesaleCasesTest {

    static final String FUTURE_CASES_FILE = "pricing/entrecueros-wholesale-cases.json";

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseNames")
    void pricesChargedLines(String name) {
        WholesaleCase wholesaleCase = loadCases().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
        Map<Long, ProductEntity> products = new HashMap<>();
        Map<String, BigDecimal> qtyByVolumeKey = new HashMap<>();
        for (LineSpec line : wholesaleCase.lines()) {
            ProductEntity product = products.computeIfAbsent(line.productId(), id -> product(line));
            EntrecuerosPriceLists.addVolumeQuantity(
                    qtyByVolumeKey, product.getId(), product, hardware(line), new BigDecimal(line.qty()));
        }

        BigDecimal saleTotal = BigDecimal.ZERO;
        for (LineSpec line : wholesaleCase.lines()) {
            ProductEntity product = products.get(line.productId());
            BigDecimal quantity = new BigDecimal(line.qty());
            BigDecimal unitPrice = EntrecuerosPriceLists.resolveChargedUnitPrice(
                    product, hardware(line), quantity, qtyByVolumeKey);
            assertThat(unitPrice)
                    .as(name + " / " + line.id())
                    .isEqualByComparingTo(wholesaleCase.expectedUnitPrices().get(line.id()));
            BigDecimal lineTotal = EntrecuerosPriceLists.lineTotal(unitPrice, quantity);
            saleTotal = saleTotal.add(lineTotal);
        }
        if (wholesaleCase.expectedSaleTotal() != null) {
            assertThat(saleTotal).as(name).isEqualByComparingTo(wholesaleCase.expectedSaleTotal());
        }
    }

    static Stream<String> caseNames() {
        return loadCases().stream().map(WholesaleCase::name);
    }

    /**
     * In-memory stand-in for {@value #FUTURE_CASES_FILE}.
     */
    static List<WholesaleCase> loadCases() {
        return List.of(
                c("12 casual + 1 leather wallet",
                        Map.of("cincho", "75.00", "wallet", "55.00"),
                        line("cincho", "CASUAL", 1, 12),
                        line("wallet", "WALLET_LEATHER", 2, 1)),
                c("6 leather + 6 casual keep own tiers",
                        Map.of("wallet", "55.00", "cincho", "80.00"),
                        line("wallet", "WALLET_LEATHER", 1, 6),
                        line("cincho", "CASUAL", 2, 6)),
                c("3 + 3 no courtesy",
                        Map.of("wallet", "65.00", "cincho", "90.00"),
                        line("wallet", "WALLET_LEATHER", 1, 3),
                        line("cincho", "CASUAL", 2, 3)),
                c("20 leather + 1 casual",
                        Map.of("wallet", "55.00", "cincho", "75.00"),
                        line("wallet", "WALLET_LEATHER", 1, 20),
                        line("cincho", "CASUAL", 2, 1)),
                c("6 casual + 1 wallet",
                        Map.of("cincho", "80.00", "wallet", "55.00"),
                        "535.00",
                        line("cincho", "CASUAL", 1, 6),
                        line("wallet", "WALLET_LEATHER", 2, 1)),
                c("5 + 1 of the same type triggers courtesy",
                        Map.of("cinchoA", "80.00", "cinchoB", "80.00", "wallet", "55.00"),
                        line("cinchoA", "CASUAL", 1, 5),
                        line("cinchoB", "CASUAL", 2, 1),
                        line("wallet", "WALLET_LEATHER", 3, 1)),
                c("6 packaging triggers nothing",
                        Map.of("pack", "8.00", "wallet", "100.00"),
                        line("pack", "PACKAGING", 1, 6).sale("8.00"),
                        line("wallet", "WALLET_LEATHER", 2, 1)),
                c("packaging never receives courtesy",
                        Map.of("wallet", "55.00", "pack", "20.00"),
                        line("wallet", "WALLET_LEATHER", 1, 6),
                        line("pack", "PACKAGING", 2, 1).tiers("20", "15", "12", "9")),
                c("reversible stays 100 under courtesy",
                        Map.of("cincho", "80.00", "reversible", "100.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("reversible", "REVERSIBLE", 2, 1)),
                c("B-1 stays 40 under courtesy",
                        Map.of("cincho", "80.00", "b1", "40.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("b1", "WALLET_SYNTHETIC", 2, 1).code("B-1")),
                c("B1 stays 40 under courtesy",
                        Map.of("cincho", "80.00", "b1", "40.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("b1", "WALLET_SYNTHETIC", 2, 1).code("b1")),
                c("trimmed B-1 at qty 3 stays 40",
                        Map.of("b1", "40.00"),
                        line("b1", "WALLET_SYNTHETIC", 1, 3).code(" B-1 ")),
                c("B-10 at qty 3 is 30",
                        Map.of("wallet", "30.00"),
                        line("wallet", "WALLET_SYNTHETIC", 1, 3).code("B-10")),
                c("B-10 under courtesy is 30",
                        Map.of("cincho", "80.00", "wallet", "30.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("wallet", "WALLET_SYNTHETIC", 2, 1).code("B-10")),
                c("B-19 at qty 3 is 30",
                        Map.of("wallet", "30.00"),
                        line("wallet", "WALLET_SYNTHETIC", 1, 3).code("B-19")),
                c("B-100 at qty 6 is 30",
                        Map.of("wallet", "30.00"),
                        line("wallet", "WALLET_SYNTHETIC", 1, 6).code("B-100")),
                c("B-100 under courtesy is 30",
                        Map.of("leather", "55.00", "wallet", "30.00"),
                        line("leather", "WALLET_LEATHER", 1, 6),
                        line("wallet", "WALLET_SYNTHETIC", 2, 1).code("B-100")),
                c("nino under courtesy is 45",
                        Map.of("cincho", "80.00", "nino", "45.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("nino", "NINO", 2, 1)),
                c("dama under courtesy is 60",
                        Map.of("cincho", "80.00", "dama", "60.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("dama", "DAMA", 2, 1)),
                c("synthetic under courtesy is 30",
                        Map.of("cincho", "80.00", "wallet", "30.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("wallet", "WALLET_SYNTHETIC", 2, 1).code("S-2")),
                c("cardholder under courtesy is 6",
                        Map.of("cincho", "80.00", "card", "6.00"),
                        line("cincho", "CASUAL", 1, 6),
                        line("card", "CARDHOLDER_SYNTHETIC", 2, 1)),
                c("nino edge 2",
                        Map.of("nino", "65.00"),
                        line("nino", "NINO", 1, 2)),
                c("nino edge 3",
                        Map.of("nino", "45.00"),
                        line("nino", "NINO", 1, 3)),
                c("dama edge 2",
                        Map.of("dama", "65.00"),
                        line("dama", "DAMA", 1, 2)),
                c("dama edge 3",
                        Map.of("dama", "60.00"),
                        line("dama", "DAMA", 1, 3)),
                c("synthetic edge 2",
                        Map.of("wallet", "40.00"),
                        line("wallet", "WALLET_SYNTHETIC", 1, 2).code("S-4")),
                c("synthetic edge 3",
                        Map.of("wallet", "30.00"),
                        line("wallet", "WALLET_SYNTHETIC", 1, 3).code("S-4")),
                c("cardholder edge 2",
                        Map.of("card", "10.00"),
                        line("card", "CARDHOLDER_SYNTHETIC", 1, 2)),
                c("cardholder edge 3",
                        Map.of("card", "6.00"),
                        line("card", "CARDHOLDER_SYNTHETIC", 1, 3)),
                c("casual edge 2",
                        Map.of("two", "100.00"),
                        line("two", "CASUAL", 1, 2)),
                c("casual edge 3",
                        Map.of("three", "90.00"),
                        line("three", "CASUAL", 1, 3)),
                c("casual edge 5",
                        Map.of("five", "90.00"),
                        line("five", "CASUAL", 1, 5)),
                c("casual edge 6",
                        Map.of("six", "80.00"),
                        line("six", "CASUAL", 1, 6)),
                c("casual edge 11",
                        Map.of("eleven", "80.00"),
                        line("eleven", "CASUAL", 1, 11)),
                c("casual edge 12",
                        Map.of("twelve", "75.00"),
                        line("twelve", "CASUAL", 1, 12)),
                c("leather edge 2",
                        Map.of("two", "100.00"),
                        line("two", "WALLET_LEATHER", 1, 2)),
                c("leather edge 3",
                        Map.of("three", "65.00"),
                        line("three", "WALLET_LEATHER", 1, 3)),
                c("leather edge 5",
                        Map.of("five", "65.00"),
                        line("five", "WALLET_LEATHER", 1, 5)),
                c("leather edge 6",
                        Map.of("six", "55.00"),
                        line("six", "WALLET_LEATHER", 1, 6)),
                c("leather edge 11",
                        Map.of("eleven", "55.00"),
                        line("eleven", "WALLET_LEATHER", 1, 11)),
                c("leather edge 12",
                        Map.of("twelve", "55.00"),
                        line("twelve", "WALLET_LEATHER", 1, 12)),
                c("casual configured tiers at 6",
                        Map.of("cincho", "85.00"),
                        line("cincho", "CASUAL", 1, 6).tiers("110", "95", "85", "70")),
                c("casual configured tiers at 12",
                        Map.of("cincho", "70.00"),
                        line("cincho", "CASUAL", 1, 12).tiers("110", "95", "85", "70")),
                c("casual configured courtesy uses top tier",
                        Map.of("cincho", "70.00", "wallet", "55.00"),
                        line("cincho", "CASUAL", 1, 1).tiers("110", "95", "85", "70"),
                        line("wallet", "WALLET_LEATHER", 2, 6)),
                c("untyped product triggers courtesy",
                        Map.of("extra", "20.00", "wallet", "55.00"),
                        line("extra", "PRODUCT", 1, 6).tiers("40", "30", "20", "10"),
                        line("wallet", "WALLET_LEATHER", 2, 1)),
                c("untyped product courtesy uses top configured tier",
                        Map.of("extra", "10.00", "wallet", "55.00"),
                        line("extra", "PRODUCT", 1, 1).tiers("40", "30", "20", "10"),
                        line("wallet", "WALLET_LEATHER", 2, 6)),
                c("untyped courtesy falls back when 12 tier is missing",
                        Map.of("extra", "20.00", "wallet", "55.00"),
                        line("extra", "PRODUCT", 1, 1).tiers("40", "30", "20", null),
                        line("wallet", "WALLET_LEATHER", 2, 6)),
                c("rounded line totals sum to the sale total",
                        Map.of("a", "10.01", "b", "10.00"),
                        "50.03",
                        line("a", "PRODUCT", 1, 3).tiers("10.005", null, null, null),
                        line("b", "PRODUCT", 2, 2).tiers("10.004", null, null, null))
        );
    }

    private static WholesaleCase c(String name, Map<String, String> expected, LineSpec... lines) {
        return new WholesaleCase(name, List.of(lines), expected, null);
    }

    private static WholesaleCase c(
            String name,
            Map<String, String> expected,
            String saleTotal,
            LineSpec... lines
    ) {
        return new WholesaleCase(name, List.of(lines), expected, saleTotal);
    }

    private static LineSpec line(String id, String kind, long productId, int qty) {
        return new LineSpec(id, kind, productId, qty, null, null, null, null, null, null);
    }

    private static ProductEntity product(LineSpec line) {
        ProductEntity.ProductEntityBuilder builder = ProductEntity.builder()
                .id(line.productId())
                .code(line.code() != null ? line.code() : defaultCode(line))
                .name(defaultName(line.kind()));
        if ("CASUAL".equals(line.kind()) || "NINO".equals(line.kind()) || "DAMA".equals(line.kind())) {
            builder.cinchoType("CASUAL");
        }
        if ("REVERSIBLE".equals(line.kind())) {
            builder.cinchoType("REVERSIBLE");
        }
        if (line.salePrice() != null) {
            builder.salePrice(new BigDecimal(line.salePrice()));
        }
        if (line.tier1() != null) {
            builder.entrecuerosPriceUnit(new BigDecimal(line.tier1()));
        }
        if (line.tier3() != null) {
            builder.entrecuerosPriceQty3(new BigDecimal(line.tier3()));
        }
        if (line.tier6() != null) {
            builder.entrecuerosPriceQty6(new BigDecimal(line.tier6()));
        }
        if (line.tier12() != null) {
            builder.entrecuerosPriceQty12(new BigDecimal(line.tier12()));
        }
        return builder.build();
    }

    private static String hardware(LineSpec line) {
        return switch (line.kind()) {
            case "NINO" -> "NINO";
            case "DAMA" -> "DAMA";
            case "WALLET_LEATHER" -> "LEVIS";
            case "WALLET_SYNTHETIC", "CARDHOLDER_SYNTHETIC" -> "SINTETICO:LEVIS";
            default -> "NUEVO";
        };
    }

    private static String defaultName(String kind) {
        return switch (kind) {
            case "CASUAL", "NINO", "DAMA" -> "Cincho casual";
            case "REVERSIBLE" -> "Cincho reversible";
            case "WALLET_LEATHER", "WALLET_SYNTHETIC" -> "Billetera clasica";
            case "CARDHOLDER_SYNTHETIC" -> "Tarjetero sintetico";
            case "PACKAGING" -> "Bolsa para cinchos";
            default -> "Accesorio";
        };
    }

    private static String defaultCode(LineSpec line) {
        if ("PACKAGING".equals(line.kind())) {
            return "SUM-BOLSA-" + line.productId();
        }
        return "SKU-" + line.productId();
    }

    record LineSpec(
            String id,
            String kind,
            long productId,
            int qty,
            String code,
            String salePrice,
            String tier1,
            String tier3,
            String tier6,
            String tier12
    ) {
        LineSpec code(String code) {
            return new LineSpec(id, kind, productId, qty, code, salePrice, tier1, tier3, tier6, tier12);
        }

        LineSpec sale(String salePrice) {
            return new LineSpec(id, kind, productId, qty, code, salePrice, tier1, tier3, tier6, tier12);
        }

        /**
         * @param tier1 unit, tier3 qty 3, tier6 qty 6, tier12 qty 12
         */
        LineSpec tiers(String tier1, String tier3, String tier6, String tier12) {
            return new LineSpec(id, kind, productId, qty, code, salePrice, tier1, tier3, tier6, tier12);
        }
    }

    record WholesaleCase(
            String name,
            List<LineSpec> lines,
            Map<String, String> expectedUnitPrices,
            String expectedSaleTotal
    ) {
    }
}
