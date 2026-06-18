package com.fossiles.fossilescorebackend.infrastructure.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Catálogo estático — Clasificación de rutas LF (CA / CB / CC).
 * Código: R{routeNumber:02d}0{locationNumber:02d}
 */
public final class DeliveryRouteCatalog {

    private static final Pattern CODE_PATTERN = Pattern.compile("^R(\\d{2})0(\\d{2})$");

    private DeliveryRouteCatalog() {
    }

    public record RouteLocation(
            String regionCode,
            int routeNumber,
            int locationNumber,
            String label,
            List<String> aliases) {
        String code() {
            return buildRouteLocationCode(routeNumber, locationNumber);
        }
    }

    public record ParsedRouteLocation(
            String code,
            String regionCode,
            int routeNumber,
            int locationNumber,
            String label) {
    }

    public record RouteSuggestion(String code, String label, String regionCode, int routeNumber) {
    }

    private static final List<RouteLocation> LOCATIONS = List.of(
            loc("CA", 1, 1, "Zacapa", "ZACAPA"),
            loc("CA", 1, 2, "Teculután", "TECUULTAN", "TECUUTLAN"),
            loc("CA", 1, 3, "Chiquimula", "CHIQUIMULA"),
            loc("CA", 1, 4, "Esquipulas", "ESQUIPULAS"),
            loc("CA", 2, 1, "Izabal", "IZABAL"),
            loc("CA", 2, 2, "Puerto Barrios", "PUERTO BARRIOS", "BARRIOS"),
            loc("CA", 2, 3, "Petén", "PETEN", "FLORES"),
            loc("CA", 3, 1, "Jalapa", "JALAPA"),
            loc("CA", 3, 2, "Jutiapa", "JUTIAPA"),
            loc("CA", 3, 3, "Santa Rosa", "SANTA ROSA", "CUILAPA"),
            loc("CA", 4, 1, "Cobán", "COBAN"),
            loc("CA", 4, 2, "Progreso", "PROGRESO"),
            loc("CA", 4, 3, "Alta Verapaz", "ALTA VERAPAZ"),
            loc("CA", 4, 4, "Baja Verapaz", "BAJA VERAPAZ", "SALAMA"),
            loc("CB", 5, 1, "Escuintla", "ESCUINTLA"),
            loc("CB", 5, 2, "Patulul", "PATULUL"),
            loc("CB", 5, 3, "Amatitlán", "AMATITLAN"),
            loc("CB", 5, 4, "Santa Lucía Cotz.", "SANTA LUCIA COTZ", "COTZUMALGUAPA", "COTZ"),
            loc("CB", 5, 5, "Tiquisate", "TIQUISATE"),
            loc("CB", 5, 6, "Suchitepéquez", "SUCHITEPEQUEZ"),
            loc("CB", 6, 1, "Coatepeque", "COATEPEQUE"),
            loc("CB", 6, 2, "Retalhuleu", "RETALHULEU"),
            loc("CB", 6, 3, "Malacatán", "MALACATAN"),
            loc("CB", 6, 4, "Mazatenango", "MAZATENANGO"),
            loc("CB", 7, 1, "Chimaltenango", "CHIMALTENANGO"),
            loc("CB", 7, 2, "Quiché", "QUICHE", "SANTA CRUZ DEL QUICHE"),
            loc("CB", 7, 3, "Sololá", "SOLOLA"),
            loc("CB", 7, 4, "Tecpán", "TECPAN"),
            loc("CB", 8, 1, "San Marcos", "SAN MARCOS"),
            loc("CB", 8, 2, "Huehuetenango", "HUEHUETENANGO"),
            loc("CB", 8, 3, "Quetzaltenango", "QUETZALTENANGO", "XELA"),
            loc("CB", 8, 4, "Totonicapán", "TOTONICAPAN"),
            loc("CC", 9, 1, "Ciudad", "CIUDAD", "GUATEMALA", "GUATEMALA CITY", "CAPITAL"),
            loc("CC", 9, 2, "Villa Nueva", "VILLA NUEVA"),
            loc("CC", 9, 3, "Mixco", "MIXCO"),
            loc("CC", 9, 4, "Puerto San José", "PUERTO SAN JOSE", "SAN JOSE"),
            loc("CC", 9, 5, "Sacatepéquez", "SACATEPEQUEZ", "ANTIGUA", "LA ANTIGUA"),
            loc("CC", 10, 1, "Otros clientes", "OTROS", "OTROS CLIENTES")
    );

    private static final Map<String, RouteLocation> BY_CODE = buildIndex();

    private static Map<String, RouteLocation> buildIndex() {
        Map<String, RouteLocation> map = new LinkedHashMap<>();
        for (RouteLocation loc : LOCATIONS) {
            map.put(loc.code(), loc);
        }
        return Map.copyOf(map);
    }

    private static RouteLocation loc(String region, int route, int location, String label, String... aliases) {
        List<String> aliasList = new ArrayList<>();
        aliasList.add(label);
        if (aliases != null) {
            for (String a : aliases) {
                aliasList.add(a);
            }
        }
        return new RouteLocation(region, route, location, label, List.copyOf(aliasList));
    }

    public static String buildRouteLocationCode(int routeNumber, int locationNumber) {
        if (routeNumber < 1 || locationNumber < 1) {
            return "";
        }
        return String.format(Locale.ROOT, "R%02d0%02d", routeNumber, locationNumber);
    }

    public static boolean isValidRouteLocationCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return BY_CODE.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }

    public static Optional<ParsedRouteLocation> parseRouteLocationCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        RouteLocation known = BY_CODE.get(normalized);
        if (known != null) {
            return Optional.of(new ParsedRouteLocation(
                    normalized,
                    known.regionCode(),
                    known.routeNumber(),
                    known.locationNumber(),
                    known.label()));
        }
        var matcher = CODE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int routeNumber = Integer.parseInt(matcher.group(1));
        int locationNumber = Integer.parseInt(matcher.group(2));
        String label = LOCATIONS.stream()
                .filter(l -> l.routeNumber() == routeNumber && l.locationNumber() == locationNumber)
                .map(RouteLocation::label)
                .findFirst()
                .orElse(normalized);
        String regionCode = LOCATIONS.stream()
                .filter(l -> l.routeNumber() == routeNumber && l.locationNumber() == locationNumber)
                .map(RouteLocation::regionCode)
                .findFirst()
                .orElse(null);
        return Optional.of(new ParsedRouteLocation(normalized, regionCode, routeNumber, locationNumber, label));
    }

    public static Optional<RouteSuggestion> suggestRouteLocationCode(String address, String name) {
        String haystack = normalizeRouteText((name != null ? name + " " : "") + (address != null ? address : ""));
        if (haystack.isBlank()) {
            return Optional.empty();
        }
        RouteLocation best = null;
        int bestScore = 0;
        for (RouteLocation loc : LOCATIONS) {
            for (String alias : loc.aliases()) {
                String term = normalizeRouteText(alias);
                if (term.length() < 3) {
                    continue;
                }
                if (haystack.contains(term) && term.length() > bestScore) {
                    bestScore = term.length();
                    best = loc;
                }
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(new RouteSuggestion(
                best.code(),
                best.label(),
                best.regionCode(),
                best.routeNumber()));
    }

    public static String normalizeRouteText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static List<Map<String, Object>> getCatalogTree() {
        List<Map<String, Object>> regions = new ArrayList<>();
        for (String regionCode : List.of("CA", "CB", "CC")) {
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("code", regionCode);
            region.put("label", regionLabel(regionCode));
            List<Map<String, Object>> routes = new ArrayList<>();
            LOCATIONS.stream()
                    .filter(l -> regionCode.equals(l.regionCode()))
                    .map(RouteLocation::routeNumber)
                    .distinct()
                    .sorted()
                    .forEach(routeNumber -> {
                        Map<String, Object> route = new LinkedHashMap<>();
                        route.put("routeNumber", routeNumber);
                        List<Map<String, Object>> locations = LOCATIONS.stream()
                                .filter(l -> l.routeNumber() == routeNumber)
                                .sorted(Comparator.comparingInt(RouteLocation::locationNumber))
                                .map(l -> {
                                    Map<String, Object> locMap = new LinkedHashMap<>();
                                    locMap.put("code", l.code());
                                    locMap.put("label", l.label());
                                    locMap.put("locationNumber", l.locationNumber());
                                    return locMap;
                                })
                                .toList();
                        route.put("locations", locations);
                        routes.add(route);
                    });
            region.put("routes", routes);
            regions.add(region);
        }
        return regions;
    }

    public static String regionLabel(String regionCode) {
        if (regionCode == null) {
            return "—";
        }
        return switch (regionCode.toUpperCase(Locale.ROOT)) {
            case "CA" -> "Región CA";
            case "CB" -> "Región CB";
            case "CC" -> "Región CC";
            default -> regionCode;
        };
    }

    public static int regionSortOrder(String regionCode) {
        if (regionCode == null) {
            return 99;
        }
        return switch (regionCode.toUpperCase(Locale.ROOT)) {
            case "CA" -> 1;
            case "CB" -> 2;
            case "CC" -> 3;
            default -> 99;
        };
    }
}
