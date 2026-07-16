package com.aihotspot.core.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai-hotspot.mail.provider", havingValue = "mock", matchIfMissing = true)
public class MockMailProvider implements MailProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockMailProvider.class);

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public void send(String recipient, String subject, String htmlBody) {
        LOGGER.info("Mock mail accepted recipient={} subject={}", recipient, subject);
    }
}
