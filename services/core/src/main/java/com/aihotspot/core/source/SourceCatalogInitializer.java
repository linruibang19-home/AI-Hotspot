package com.aihotspot.core.source;

import com.aihotspot.core.auth.IdentityMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(20)
public class SourceCatalogInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceCatalogInitializer.class);
    private final SourceMapper sourceMapper;
    private final IdentityMapper identityMapper;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String ownerEmail;

    public SourceCatalogInitializer(
            SourceMapper sourceMapper,
            IdentityMapper identityMapper,
            ObjectMapper objectMapper,
            @Value("${ai-hotspot.source-catalog.enabled:false}") boolean enabled,
            @Value("${ai-hotspot.source-catalog.owner-email:}") String ownerEmail) {
        this.sourceMapper = sourceMapper;
        this.identityMapper = identityMapper;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ownerEmail = ownerEmail;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) return;
        IdentityMapper.UserAccount owner = identityMapper.findUserByEmail(ownerEmail.strip().toLowerCase(Locale.ROOT));
        if (owner == null) {
            log.warn("Source catalog skipped because owner account {} does not exist", ownerEmail);
            return;
        }
        List<CatalogItem> items = objectMapper.readValue(
                new ClassPathResource("source-catalog.json").getInputStream(),
                new TypeReference<List<CatalogItem>>() {});
        int inserted = 0;
        for (CatalogItem item : items) {
            UUID proposedSourceId = stableId("source:" + item.slug());
            sourceMapper.insertEntityIfAbsent(new SourceMapper.SourceEntity(
                    proposedSourceId, item.sourceName(), item.slug(), item.entityType(),
                    item.countryCode(), item.officialLevel(), item.authorityScore(),
                    item.websiteUrl(), null, "ACTIVE", 0, owner.id()));
            UUID sourceId = sourceMapper.findIdBySlug(item.slug());
            UUID endpointId = stableId("endpoint:" + item.catalogKey());
            inserted += sourceMapper.insertEndpointIfAbsent(new SourceMapper.SourceEndpoint(
                    endpointId, sourceId, item.endpointName(), item.endpointUrl(), item.endpointUrl(),
                    item.endpointType(), item.endpointType(), item.language(), item.pollingIntervalSeconds(),
                    item.displayPolicy(), item.indexPolicy(), null,
                    objectMapper.writeValueAsString(item.config()), null, item.status(), "UNKNOWN",
                    0, 0, owner.id(), "PRODUCTION", item.catalogKey()));
        }
        log.info("Source catalog ready: {} definitions, {} new endpoints", items.size(), inserted);
    }

    private UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public record CatalogItem(
            String catalogKey, String sourceName, String slug, String entityType,
            String countryCode, String officialLevel, BigDecimal authorityScore,
            String websiteUrl, String endpointName, String endpointUrl, String endpointType,
            String language, int pollingIntervalSeconds, String displayPolicy,
            String indexPolicy, String status, Map<String, Object> config) {}
}
