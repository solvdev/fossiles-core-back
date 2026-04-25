package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmailConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmailConfigRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailConfigRepository emailConfigRepository;

    /**
     * Envía un email usando la configuración activa
     * @param to Destinatario
     * @param subject Asunto
     * @param body Cuerpo del mensaje (HTML)
     * @param attachmentName Nombre del archivo adjunto (opcional)
     * @param attachmentContent Contenido del archivo adjunto (opcional)
     * @return true si se envió correctamente
     */
    public boolean sendEmail(String to, String subject, String body, String attachmentName, ByteArrayOutputStream attachmentContent) {
        EmailConfigEntity config = emailConfigRepository.findByIsActiveTrue()
                .orElseThrow(() -> new RuntimeException("No active email configuration found"));

        try {
            JavaMailSender mailSender = createMailSender(config);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(config.getFromEmail(), config.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, true); // true = HTML

            if (attachmentName != null && attachmentContent != null) {
                ByteArrayResource resource = new ByteArrayResource(attachmentContent.toByteArray());
                helper.addAttachment(attachmentName, resource);
            }

            mailSender.send(message);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Error sending email: " + e.getMessage(), e);
        }
    }

    /**
     * Envía un email simple sin adjuntos
     */
    public boolean sendSimpleEmail(String to, String subject, String body) {
        return sendEmail(to, subject, body, null, null);
    }

    /**
     * Crea un JavaMailSender configurado con los parámetros de EmailConfigEntity
     */
    private JavaMailSender createMailSender(EmailConfigEntity config) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(config.getSmtpHost());
        mailSender.setPort(config.getSmtpPort());
        mailSender.setUsername(config.getUsername());
        mailSender.setPassword(config.getPassword());

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        
        if (Boolean.TRUE.equals(config.getUseTls())) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        
        if (Boolean.TRUE.equals(config.getUseSsl())) {
            props.put("mail.smtp.ssl.enable", "true");
        }

        return mailSender;
    }

    /**
     * Prueba la conexión SMTP con la configuración activa
     * Intenta crear una sesión y conectarse al servidor SMTP
     */
    public boolean testConnection() {
        EmailConfigEntity config = emailConfigRepository.findByIsActiveTrue()
                .orElseThrow(() -> new RuntimeException("No active email configuration found"));

        try {
            JavaMailSenderImpl mailSender = (JavaMailSenderImpl) createMailSender(config);
            
            // Intentar crear una sesión SMTP para verificar la conexión
            mailSender.getSession();
            
            // Si llegamos aquí, la configuración es válida
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Connection test failed: " + e.getMessage(), e);
        }
    }
}

