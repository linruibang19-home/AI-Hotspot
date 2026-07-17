package com.aihotspot.core.source;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SourceService {

    private static final Set<String> ENTITY_TYPES = Set.of("COMPANY", "RESEARCH", "MEDIA", "COMMUNITY", "PROJECT", "OTHER");
    private static final Set<String> OFFICIAL_LEVELS = Set.of("OFFICIAL", "FIRST_PARTY", "THIRD_PARTY");
    private static final Set<String> ENDPOINT_TYPES = Set.of("RSS", "ATOM", "WEBSITE", "SITEMAP", "GITHUB", "HUGGING_FACE", "ARXIV", "OPENREVIEW", "HACKER_NEWS", "PUBLIC_MEDIA", "PUBLIC_COMMUNITY");
    private static final Set<String> DISPLAY_POLICIES = Set.of("FULLTEXT_ALLOWED", "SUMMARY_ONLY", "LINK_ONLY", "HIDDEN");
    private static final Set<String> INDEX_POLICIES = Set.of("PUBLIC_RAG", "PRIVATE_RAG", "METADATA_ONLY", "NO_INDEX");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Set<String> COMMON_CONFIG_KEYS = Set.of("timeoutSeconds", "maxResponseBytes", "userAgent", "respectRobots", "owner", "repository", "resource", "categories", "venueIds", "feed");

    private final SourceMapper mapper;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public SourceService(SourceMapper mapper, AuditService audit, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CreatedSource create(CreateSource command, AppUserPrincipal actor, HttpServletRequest request) {
        validate(command);
        String normalizedUrl = normalizeAndValidatePublicUrl(command.endpointUrl());
        UUID sourceId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        mapper.insertEntity(new SourceMapper.SourceEntity(
                sourceId, command.name().strip(), command.slug().strip(), command.entityType(), blankToNull(command.countryCode()),
                command.officialLevel(), command.authorityScore(), blankToNull(command.websiteUrl()), blankToNull(command.logoUrl()),
                "DRAFT", 0, actor.id()));
        mapper.insertEndpoint(new SourceMapper.SourceEndpoint(
                endpointId, sourceId, command.endpointName().strip(), command.endpointUrl().strip(), normalizedUrl,
                command.endpointType(), command.endpointType(), blankToNull(command.language()), command.pollingIntervalSeconds(),
                command.displayPolicy(), command.indexPolicy(), command.authorityOverride(), json(command.config()), null,
                "DRAFT", "UNKNOWN", 0, 0, actor.id()));
        audit.record(actor.id(), "SOURCE_CREATED", "SOURCE_ENTITY", sourceId, null,
                json(Map.of("name", command.name(), "endpointId", endpointId, "endpointType", command.endpointType(), "url", normalizedUrl)), request);
        return new CreatedSource(sourceId, endpointId);
    }

    public List<SourceMapper.SourceSummary> list(String status, String query, int limit) {
        String safeStatus = status == null || status.isBlank() ? null : status;
        if (safeStatus != null && !Set.of("DRAFT", "ACTIVE", "PAUSED", "ARCHIVED").contains(safeStatus)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "信源状态不正确");
        }
        return mapper.list(safeStatus, query == null || query.isBlank() ? null : query.strip(), Math.min(Math.max(limit, 1), 100));
    }

    public SourceView get(UUID sourceId) {
        SourceMapper.SourceDetail source = mapper.findById(sourceId);
        if (source == null) throw new ApiException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "信源不存在");
        return new SourceView(source, mapper.listBySourceId(sourceId));
    }

    public SourceMapper.SourceMetrics metrics() { return mapper.metrics(); }

    @Transactional
    public ProbeResult probe(UUID endpointId, long version, AppUserPrincipal actor, HttpServletRequest request) {
        SourceMapper.EndpointDetail endpoint = requireEndpoint(endpointId);
        if (endpoint.version() != version) throw versionConflict();
        URI uri = URI.create(normalizeAndValidatePublicUrl(endpoint.url()));
        long started = System.nanoTime();
        Integer status = null;
        String health = "FAILED";
        String message;
        try {
            HttpRequest probeRequest = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "AI-Hotspot-Probe/0.2 (+https://aihotspot.local)")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(probeRequest, HttpResponse.BodyHandlers.discarding());
            status = response.statusCode();
            health = status >= 200 && status < 400 ? "HEALTHY" : "WARNING";
            message = "HTTP " + status;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            message = "探测被中断";
        } catch (Exception exception) {
            message = "连接失败：" + exception.getClass().getSimpleName();
        }
        long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (mapper.updateProbe(endpointId, health, status, latency, "HEALTHY".equals(health), version) != 1) throw versionConflict();
        audit.record(actor.id(), "SOURCE_ENDPOINT_PROBED", "SOURCE_ENDPOINT", endpointId,
                json(Map.of("healthStatus", endpoint.healthStatus(), "version", version)),
                json(nullableMap("healthStatus", health, "httpStatus", status, "latencyMs", latency, "message", message)), request);
        return new ProbeResult(endpointId, health, status, latency, message, version + 1);
    }

    @Transactional
    public SourceMapper.EndpointDetail changeStatus(UUID endpointId, String status, long version, AppUserPrincipal actor, HttpServletRequest request) {
        if (!Set.of("ACTIVE", "PAUSED").contains(status)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "只允许启用或暂停入口");
        SourceMapper.EndpointDetail endpoint = requireEndpoint(endpointId);
        if ("ACTIVE".equals(status) && !Set.of("HEALTHY", "WARNING").contains(endpoint.healthStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROBE_REQUIRED", "启用前必须通过试抓取");
        }
        if (mapper.updateEndpointStatus(endpointId, status, version) != 1) throw versionConflict();
        if ("ACTIVE".equals(status) && mapper.activateEntity(endpoint.sourceEntityId()) == 1) {
            audit.record(actor.id(), "SOURCE_ENTITY_ACTIVATED", "SOURCE_ENTITY", endpoint.sourceEntityId(),
                    json(Map.of("status", "DRAFT")), json(Map.of("status", "ACTIVE")), request);
        }
        audit.record(actor.id(), "SOURCE_ENDPOINT_" + status, "SOURCE_ENDPOINT", endpointId,
                json(Map.of("status", endpoint.status(), "version", endpoint.version())),
                json(Map.of("status", status, "version", version + 1)), request);
        return requireEndpoint(endpointId);
    }

    public Map<String, Object> connectorSchemas() {
        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("common", Map.of("timeoutSeconds", "integer:1..30", "maxResponseBytes", "integer:1024..10485760", "userAgent", "string", "respectRobots", "boolean"));
        schemas.put("GITHUB", Map.of("owner", "string", "repository", "string", "resource", "releases|commits|issues"));
        schemas.put("ARXIV", Map.of("categories", "string[]"));
        schemas.put("OPENREVIEW", Map.of("venueIds", "string[]"));
        schemas.put("HACKER_NEWS", Map.of("feed", "top|new|best"));
        return schemas;
    }

    private void validate(CreateSource c) {
        if (c.name() == null || c.name().isBlank() || c.name().length() > 120) invalid("信源名称长度必须为 1 到 120");
        if (c.slug() == null || !SLUG.matcher(c.slug()).matches()) invalid("slug 只能包含小写字母、数字和连字符");
        if (!ENTITY_TYPES.contains(c.entityType())) invalid("主体类型不正确");
        if (!OFFICIAL_LEVELS.contains(c.officialLevel())) invalid("官方等级不正确");
        if (!ENDPOINT_TYPES.contains(c.endpointType())) invalid("Endpoint 类型不正确或不在项目范围");
        if (!DISPLAY_POLICIES.contains(c.displayPolicy()) || !INDEX_POLICIES.contains(c.indexPolicy())) invalid("展示或索引策略不正确");
        if (c.authorityScore() == null || c.authorityScore().compareTo(BigDecimal.ZERO) < 0 || c.authorityScore().compareTo(new BigDecimal("100")) > 0) invalid("权威分必须在 0 到 100 之间");
        if (c.pollingIntervalSeconds() < 300 || c.pollingIntervalSeconds() > 2592000) invalid("轮询间隔必须在 5 分钟到 30 天之间");
        if (c.config() != null) {
            Set<String> disallowed = new java.util.HashSet<>(c.config().keySet());
            disallowed.removeAll(COMMON_CONFIG_KEYS);
            if (!disallowed.isEmpty()) invalid("Connector 配置包含未知字段：" + String.join(", ", disallowed));
            if (c.config().keySet().stream().anyMatch(key -> key.toLowerCase(Locale.ROOT).contains("password") || key.toLowerCase(Locale.ROOT).contains("token") || key.toLowerCase(Locale.ROOT).contains("secret"))) invalid("敏感信息必须使用 credentialRef，不能写入 Connector 配置");
        }
    }

    String normalizeAndValidatePublicUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null) invalid("URL 必须是无凭据的公开 HTTP/HTTPS 地址");
            String host = IDN.toASCII(uri.getHost().toLowerCase(Locale.ROOT));
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) invalid("URL 解析到了不允许访问的私有或本地地址");
            }
            int port = uri.getPort();
            if (port != -1 && port != 80 && port != 443) invalid("MVP 信源只允许标准 HTTP/HTTPS 端口");
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            return new URI(scheme, null, host, port, path, uri.getRawQuery(), null).normalize().toASCIIString();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SOURCE_URL", "URL 无法解析或域名不可用");
        }
    }

    private SourceMapper.EndpointDetail requireEndpoint(UUID id) {
        SourceMapper.EndpointDetail endpoint = mapper.findEndpointById(id);
        if (endpoint == null) throw new ApiException(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "信源入口不存在");
        return endpoint;
    }

    private ApiException versionConflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "对象已被其他操作更新，请刷新后重试"); }
    private void invalid(String message) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SOURCE", message); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private String json(Object value) {
        try { return value == null ? null : objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize source data", exception); }
    }
    private Map<String, Object> nullableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) if (values[index + 1] != null) result.put((String) values[index], values[index + 1]);
        return result;
    }

    public record CreateSource(
            String name, String slug, String entityType, String countryCode, String officialLevel,
            BigDecimal authorityScore, String websiteUrl, String logoUrl, String endpointName,
            String endpointUrl, String endpointType, String language, int pollingIntervalSeconds,
            String displayPolicy, String indexPolicy, BigDecimal authorityOverride, Map<String, Object> config) {}
    public record CreatedSource(UUID sourceId, UUID endpointId) {}
    public record ProbeResult(UUID endpointId, String healthStatus, Integer httpStatus, long latencyMs, String message, long version) {}
    public record SourceView(SourceMapper.SourceDetail source, List<SourceMapper.SourceSummary> endpoints) {}
}
