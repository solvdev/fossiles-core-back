package com.fossiles.fossilescorebackend.application.service.reconciliation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Utilidades de texto y dinero de la conciliación. */
public final class ReconText {

    private ReconText() {
    }

    /** Identificador comparable: mayúsculas y solo letras y dígitos ("58-123 456" = "58123456"). */
    public static String norm(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Llave del transportista tolerante a cómo lo escriba cada fuente ("Forza Delivery", "FORZA"). */
    public static String carrierKey(String value) {
        String n = norm(value);
        if (n.contains("FORZA")) return "FORZA";
        if (n.contains("GUATEX")) return "GUATEX";
        if (n.contains("MOTORISTA")) return "MOTORISTA";
        return n;
    }

    public static boolean isCarrierWithGuide(String carrier) {
        String k = carrierKey(carrier);
        return k.equals("FORZA") || k.equals("GUATEX");
    }

    public static boolean isCod(String paymentMethod) {
        return paymentMethod != null && paymentMethod.startsWith("CONTRA_ENTREGA");
    }

    public static boolean isBankPayment(String paymentMethod) {
        return paymentMethod != null && (paymentMethod.startsWith("DEPOSITO") || paymentMethod.startsWith("TRANSFERENCIA"));
    }

    public static boolean isCardPayment(String paymentMethod) {
        return paymentMethod != null && (paymentMethod.startsWith("VISALINK") || paymentMethod.startsWith("TARJETA"));
    }

    public static boolean isPendingPayment(String paymentMethod) {
        return paymentMethod != null && paymentMethod.endsWith("_PENDIENTE");
    }

    public static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Igualdad exacta de montos al centavo, sin tolerancia. */
    public static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return money(a).compareTo(money(b)) == 0;
    }

    public static String q(BigDecimal value) {
        return "Q" + money(value).toPlainString();
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
