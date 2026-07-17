package com.aihotspot.core.source;

import com.aihotspot.core.api.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceUrlPolicyTests {

    private final SourceService service = new SourceService(null, null, new ObjectMapper());

    @Test
    void rejectsLoopbackAndPrivateAddresses() {
        assertThrows(ApiException.class, () -> service.normalizeAndValidatePublicUrl("http://127.0.0.1/feed"));
        assertThrows(ApiException.class, () -> service.normalizeAndValidatePublicUrl("http://localhost/feed"));
        assertThrows(ApiException.class, () -> service.normalizeAndValidatePublicUrl("http://10.1.2.3/feed"));
    }

    @Test
    void rejectsCredentialsNonHttpSchemesAndNonStandardPorts() {
        assertThrows(ApiException.class, () -> service.normalizeAndValidatePublicUrl("file:///etc/passwd"));
        assertThrows(ApiException.class, () -> service.normalizeAndValidatePublicUrl("https://user:pass@example.com/feed"));
        assertThrows(ApiException.class, () -> service.normalizeAndValidatePublicUrl("https://example.com:8443/feed"));
    }

    @Test
    void normalizesAValidPublicUrl() {
        assertEquals("https://example.com/feed", service.normalizeAndValidatePublicUrl("HTTPS://EXAMPLE.COM/feed#section"));
    }
}
