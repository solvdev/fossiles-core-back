package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.service.EmailService;
import com.fossiles.fossilescorebackend.application.service.PdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final PdfService pdfService;
    private final EmailService emailService;

    @PostMapping("/generate-pdf/{documentType}")
    public ResponseEntity<byte[]> generatePdf(@PathVariable String documentType, @RequestBody Map<String, Object> data) {
        ByteArrayOutputStream pdfStream = pdfService.generatePdf(documentType, data);
        byte[] pdfBytes = pdfStream.toByteArray();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", documentType.toLowerCase() + ".pdf");

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }

    @PostMapping("/generate-pdf-format/{formatId}")
    public ResponseEntity<byte[]> generatePdfWithFormat(@PathVariable Long formatId, @RequestBody Map<String, Object> data) {
        ByteArrayOutputStream pdfStream = pdfService.generatePdfWithFormat(formatId, data);
        byte[] pdfBytes = pdfStream.toByteArray();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "document.pdf");

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }

    @PostMapping("/send-email")
    public ResponseEntity<String> sendDocumentByEmail(@RequestBody Map<String, Object> request) {
        String to = (String) request.get("to");
        String subject = (String) request.get("subject");
        String body = (String) request.get("body");
        String documentType = (String) request.get("documentType");
        Map<String, Object> documentData = (Map<String, Object>) request.get("documentData");

        if (to == null || subject == null || body == null) {
            return ResponseEntity.badRequest().body("Missing required fields: to, subject, body");
        }

        try {
            ByteArrayOutputStream pdfStream = null;
            String attachmentName = null;

            if (documentType != null && documentData != null) {
                pdfStream = pdfService.generatePdf(documentType, documentData);
                attachmentName = documentType.toLowerCase() + ".pdf";
            }

            boolean sent = emailService.sendEmail(to, subject, body, attachmentName, pdfStream);
            
            if (sent) {
                return ResponseEntity.ok("Email sent successfully");
            } else {
                return ResponseEntity.internalServerError().body("Failed to send email");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error sending email: " + e.getMessage());
        }
    }
}

