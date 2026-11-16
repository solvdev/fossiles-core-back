package com.fossiles.fossilescorebackend.application.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reconoce a quién de contabilidad se refiere el Excel («GUSTAVO», «FATIMA Y ROBERTO», «EDUARDO Y ROBERTO») entre los
 * usuarios con rol CONTABILIDAD. Se compara por nombre de pila (sin tildes); lo que no tenga usuario queda aparte
 * para avisarlo en la vista previa.
 */
public final class KioskAccountantMatcher {

    private KioskAccountantMatcher() {
    }

    public record User(long id, String firstName, String lastName, String username) {
    }

    public record Result(List<Long> userIds, List<String> unmatchedNames) {
    }

    public static Result match(String text, List<User> accountingUsers) {
        Set<Long> ids = new LinkedHashSet<>();
        List<String> unmatched = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new Result(List.of(), List.of());
        }
        for (String part : text.split("(?i)\\s+Y\\s+|,|/|\\+")) {
            String name = normalize(part);
            if (name.isEmpty()) {
                continue;
            }
            User found = null;
            for (User u : accountingUsers) {
                String first = normalize(u.firstName()).split(" ")[0];
                String full = normalize(u.firstName() + " " + u.lastName());
                if (name.equals(first) || name.equals(full) || name.equals(normalize(u.username()))) {
                    found = u;
                    break;
                }
            }
            if (found != null) {
                ids.add(found.id());
            } else if (!unmatched.contains(name)) {
                unmatched.add(name);
            }
        }
        return new Result(new ArrayList<>(ids), unmatched);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String s = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }
}
