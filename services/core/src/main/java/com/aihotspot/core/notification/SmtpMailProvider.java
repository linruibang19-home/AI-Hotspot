package com.aihotspot.core.notification;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai-hotspot.mail.provider", havingValue = "smtp")
public class SmtpMailProvider implements MailProvider {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpMailProvider(JavaMailSender mailSender, @Value("${ai-hotspot.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public String providerName() {
        return "smtp";
    }

    @Override
    public void send(String recipient, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (Exception exception) {
            throw new IllegalStateException("SMTP delivery failed", exception);
        }
    }
}
