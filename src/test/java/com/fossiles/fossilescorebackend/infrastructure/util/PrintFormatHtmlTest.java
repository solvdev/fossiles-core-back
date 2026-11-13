package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrintFormatHtmlTest {
    @Test
    void removesExecutableHtmlAndDangerousUrls() {
        var result = PrintFormatHtml.sanitize("<script>alert(1)</script><img src='https://example.com/logo.png' onerror='alert(1)'>"
                + "<a href='javascript:alert(1)'>Texto</a><iframe src='https://example.com'></iframe>");
        assertThat(result).doesNotContain("script", "onerror", "javascript:", "iframe")
                .contains("https://example.com/logo.png", "Texto");
    }

    @Test
    void preservesPrintStructureAndTemplateVariables() {
        var result = PrintFormatHtml.sanitize("<table><tr><td colspan='2' style='text-align:center;color:red'>"
                + "{{documentNumber}}</td></tr></table>");
        assertThat(result).contains("<table>", "colspan=\"2\"", "text-align:center", "{{documentNumber}}");
        assertThat(PrintFormatHtml.sanitize(result)).isEqualTo(result);
    }

    @Test
    void keepsLogoVariableAndEmbeddedImagesButNotDataLinks() {
        var result = PrintFormatHtml.sanitize("<img src=\"{{logo}}\" alt=\"\" />"
                + "<img src=\"data:image/png;base64,iVBORw0KGgo=\" />"
                + "<img src=\"data:text/html;base64,PHNjcmlwdD4=\" />"
                + "<a href=\"data:text/html,hola\">Enlace</a>");
        assertThat(result).contains("src=\"{{logo}}\"", "src=\"data:image/png;base64,iVBORw0KGgo", "Enlace")
                .doesNotContain("data:text/html");
        assertThat(PrintFormatHtml.sanitize(result)).isEqualTo(result);
    }
}
