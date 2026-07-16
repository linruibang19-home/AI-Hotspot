package com.aihotspot.core.notification;

public interface MailProvider {

    String providerName();

    void send(String recipient, String subject, String htmlBody);
}
