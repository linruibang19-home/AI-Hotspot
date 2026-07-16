package com.aihotspot.core.api;

import com.aihotspot.core.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String environment;
    private final String version;

    public HealthController(
            @Value("${ai-hotspot.environment}") String environment,
            @Value("${ai-hotspot.version}") String version) {
        this.environment = environment;
        this.version = version;
    }

    @GetMapping("/health")
    public Map<String, Object> health(HttpServletRequest request) {
        return Map.of(
                "status", "UP",
                "service", "core-api",
                "version", version,
                "environment", environment,
                "correlationId", request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));
    }
}
