package com.fossiles.fossilescorebackend.infrastructure.util;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.regex.Pattern;

/**
 * Limpia el HTML de los formatos de impresión con una lista permitida (OWASP): quita scripts, eventos e iframes y
 * conserva tablas, estilos, imágenes y variables {{...}}.
 */
public final class PrintFormatHtml {
    // Imágenes: URL web, variable de plantilla o imagen incrustada en base64, igual que acepta el editor.
    private static final Pattern IMAGE_SOURCE = Pattern.compile(
            "(?i)^\\s*(https?://|/|\\{\\{|data:image/(png|jpe?g|gif|webp);base64,)[^\\s\"'<>]*\\s*$");
    private static final Pattern LINK_TARGET = Pattern.compile("(?i)^\\s*(https?://|/|#|\\{\\{)[^\\s\"'<>]*\\s*$");
    private static final Pattern ENCODED_VARIABLE = Pattern.compile("%7[bB]%7[bB]([A-Za-z0-9_.-]+)%7[dD]%7[dD]");
    private static final String ZERO_WIDTH_SPACE = String.valueOf((char) 0x200B);

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("div", "span", "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
                    "strong", "b", "em", "i", "u", "s", "small", "sub", "sup", "ul", "ol", "li",
                    "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col", "img", "a")
            .allowAttributes("class", "title").globally()
            .allowAttributes("colspan", "rowspan", "scope").onElements("th", "td")
            .allowAttributes("alt", "width", "height").onElements("img")
            .allowAttributes("src").matching(IMAGE_SOURCE).onElements("img")
            .allowAttributes("href").matching(LINK_TARGET).onElements("a")
            .allowUrlProtocols("http", "https", "data")
            .allowStyling()
            .toFactory();

    private PrintFormatHtml() {}

    // Conserva tablas, estilos de impresión y variables; elimina contenido ejecutable.
    // El sanitizador separa "{{" para evitar plantillas del navegador y en src/href lo codifica como URL;
    // aquí se restaura porque la impresión reemplaza las variables como texto.
    public static String sanitize(String html) {
        if (html == null) return null;
        String clean = POLICY.sanitize(html)
                .replace("{<!-- -->{", "{{")
                .replace("{" + ZERO_WIDTH_SPACE + "{", "{{")
                .replace("{&#8203;{", "{{");
        return ENCODED_VARIABLE.matcher(clean).replaceAll("{{$1}}");
    }
}
