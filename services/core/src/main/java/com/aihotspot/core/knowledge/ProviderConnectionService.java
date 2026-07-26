package com.aihotspot.core.knowledge;

import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.api.ApiException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class ProviderConnectionService {
    private static final Set<String> CAPABILITIES = Set.of(
            "CONTENT_ANALYSIS", "RAG_GENERATION", "EMBEDDING", "RERANK", "AGENT");
    private static final Set<String> PROTOCOLS = Set.of("OPENAI_COMPATIBLE", "SILICONFLOW");
    private final JdbcTemplate jdbc;
    private final ProviderCredentialCipher cipher;
    private final ProviderEndpointPolicy endpointPolicy;
    private final RestClient ai;
    private final String internalToken;
    private final boolean ragRequireUserKey;

    public ProviderConnectionService(
            JdbcTemplate jdbc,
            ProviderCredentialCipher cipher,
            ProviderEndpointPolicy endpointPolicy,
            @Value("${ai-hotspot.ai-base-url}") String aiBaseUrl,
            @Value("${ai-hotspot.security.ai-internal-token:ai-hotspot-local-internal-token}") String internalToken,
            @Value("${ai-hotspot.security.rag-require-user-key:false}") boolean ragRequireUserKey) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.endpointPolicy = endpointPolicy;
        this.internalToken = internalToken;
        this.ragRequireUserKey = ragRequireUserKey;
        this.ai = RestClient.builder().baseUrl(aiBaseUrl).build();
    }

    public List<ConnectionView> platformConnections() {
        return jdbc.query("""
            select id,scope,display_name,vendor,api_protocol,base_url,credential_kind,
              credential_ref,key_last_four,capabilities,status,last_test_status,last_tested_at,
              last_error_code,created_at,updated_at
            from knowledge.provider_connection
            where scope='PLATFORM'
            order by status='ACTIVE' desc,display_name
            """, (rs, row) -> new ConnectionView(
                rs.getObject("id", UUID.class), rs.getString("scope"), rs.getString("display_name"),
                rs.getString("vendor"), rs.getString("api_protocol"), rs.getString("base_url"),
                rs.getString("credential_kind"), rs.getString("credential_ref"),
                rs.getString("key_last_four"), rs.getArray("capabilities") == null
                        ? List.of()
                        : List.of((String[]) rs.getArray("capabilities").getArray()),
                rs.getString("status"), rs.getString("last_test_status"),
                rs.getObject("last_tested_at", OffsetDateTime.class), rs.getString("last_error_code"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)));
    }

    @Transactional
    public ConnectionView createPlatform(AppUserPrincipal actor, SaveConnection request) {
        ValidatedConnection validated = validate(request);
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new IllegalArgumentException("新建连接必须填写 API Key");
        }
        ProviderCredentialCipher.EncryptedValue encrypted = cipher.encrypt(request.apiKey());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into knowledge.provider_connection(
              id,scope,display_name,vendor,api_protocol,base_url,credential_kind,
              credential_ciphertext,credential_nonce,key_fingerprint,key_last_four,
              capabilities,status,created_by,updated_by)
            values(?,'PLATFORM',?,?,?,?,'ENCRYPTED',?,?,?,?,?::text[],?,?,?)
            """, id, validated.displayName(), validated.vendor(), validated.protocol(),
                validated.baseUrl(), encrypted.ciphertext(), encrypted.nonce(),
                cipher.fingerprint(request.apiKey()), lastFour(request.apiKey()),
                validated.capabilities().toArray(String[]::new), "DISABLED", actor.id(), actor.id());
        return findPlatform(id);
    }

    @Transactional
    public ConnectionView updatePlatform(UUID id, AppUserPrincipal actor, SaveConnection request) {
        findPlatform(id);
        ValidatedConnection validated = validate(request);
        boolean replaceKey = request.apiKey() != null && !request.apiKey().isBlank();
        int updated;
        if (replaceKey) {
            ProviderCredentialCipher.EncryptedValue encrypted = cipher.encrypt(request.apiKey());
            updated = jdbc.update("""
                update knowledge.provider_connection
                set display_name=?,vendor=?,api_protocol=?,base_url=?,credential_kind='ENCRYPTED',
                  credential_ref=null,credential_ciphertext=?,credential_nonce=?,key_fingerprint=?,
                  key_last_four=?,capabilities=?::text[],last_test_status='NOT_TESTED',
                  last_error_code=null,status='DISABLED',updated_by=?,updated_at=now()
                where id=? and scope='PLATFORM'
                """, validated.displayName(), validated.vendor(), validated.protocol(),
                    validated.baseUrl(), encrypted.ciphertext(), encrypted.nonce(),
                    cipher.fingerprint(request.apiKey()), lastFour(request.apiKey()),
                    validated.capabilities().toArray(String[]::new), actor.id(), id);
        } else {
            updated = jdbc.update("""
                update knowledge.provider_connection
                set display_name=?,vendor=?,api_protocol=?,base_url=?,capabilities=?::text[],
                  last_test_status='NOT_TESTED',last_error_code=null,status='DISABLED',
                  updated_by=?,updated_at=now()
                where id=? and scope='PLATFORM'
                """, validated.displayName(), validated.vendor(), validated.protocol(),
                    validated.baseUrl(), validated.capabilities().toArray(String[]::new), actor.id(), id);
        }
        if (updated != 1) throw new IllegalArgumentException("Provider 连接不存在");
        jdbc.update("""
            update knowledge.provider_config
            set status='DISABLED',updated_by=?,updated_at=now()
            where connection_id=?
            """, actor.id(), id);
        return findPlatform(id);
    }

    @Transactional
    public ConnectionView setStatus(UUID id, AppUserPrincipal actor, String status) {
        String normalized = String.valueOf(status).strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DISABLED").contains(normalized)) {
            throw new IllegalArgumentException("连接状态只允许 ACTIVE 或 DISABLED");
        }
        ConnectionView connection = findPlatform(id);
        if ("ACTIVE".equals(normalized) && !"PASS".equals(connection.lastTestStatus())) {
            throw new IllegalArgumentException("连接必须先通过真实测试才能启用");
        }
        jdbc.update("""
            update knowledge.provider_connection
            set status=?,updated_by=?,updated_at=now()
            where id=? and scope='PLATFORM'
            """, normalized, actor.id(), id);
        if ("DISABLED".equals(normalized)) {
            jdbc.update("""
                update knowledge.provider_config
                set status='DISABLED',updated_by=?,updated_at=now()
                where connection_id=?
                """, actor.id(), id);
        }
        return findPlatform(id);
    }

    @Transactional
    public TestResult test(UUID id) {
        ConnectionSecret connection = secret(id);
        String capability = connection.capabilities().stream()
                .filter(value -> Set.of("RAG_GENERATION", "EMBEDDING", "RERANK").contains(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("连接没有可测试的 RAG 能力"));
        long started = System.nanoTime();
        try {
            Map<String, Object> provider = "ENCRYPTED".equals(connection.credentialKind())
                    ? override(connection, capability)
                    : null;
            Map<String, Object> response;
            if ("EMBEDDING".equals(capability)) {
                Map<String,Object> body=new LinkedHashMap<>();
                body.put("texts",List.of("AI Hotspot provider connection test"));
                if(provider!=null)body.put("provider_override",provider);
                RestClient.RequestBodySpec request=ai.post().uri("/api/v1/embed")
                        .contentType(MediaType.APPLICATION_JSON);
                if(provider!=null)request.header("X-AI-Internal-Token",internalToken);
                response=request.body(body).retrieve().body(Map.class);
                if (!(response.get("vectors") instanceof List<?> values) || values.isEmpty()) {
                    throw new IllegalStateException("Embedding 返回为空");
                }
            } else if ("RERANK".equals(capability)) {
                Map<String,Object> body=new LinkedHashMap<>();
                body.put("query","AI");body.put("documents",List.of(Map.of("id","test","text","AI Hotspot")));
                body.put("top_n",1);if(provider!=null)body.put("provider_override",provider);
                RestClient.RequestBodySpec request=ai.post().uri("/api/v1/rerank")
                        .contentType(MediaType.APPLICATION_JSON);
                if(provider!=null)request.header("X-AI-Internal-Token",internalToken);
                response=request.body(body).retrieve().body(Map.class);
                if (!(response.get("results") instanceof List<?> values) || values.isEmpty()) {
                    throw new IllegalStateException("Rerank 返回为空");
                }
            } else {
                Map<String,Object> body=new LinkedHashMap<>();
                body.put("prompt","只回答 OK");body.put("max_tokens",64);
                if(provider!=null)body.put("provider_override",provider);
                RestClient.RequestBodySpec request=ai.post().uri("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON);
                if(provider!=null)request.header("X-AI-Internal-Token",internalToken);
                response=request.body(body).retrieve().body(Map.class);
                if (String.valueOf(response.getOrDefault("text", "")).isBlank()) {
                    throw new IllegalStateException("Generation 返回为空");
                }
            }
            long latency = elapsedMillis(started);
            jdbc.update("""
                update knowledge.provider_connection
                set last_test_status='PASS',last_tested_at=now(),last_error_code=null,updated_at=now()
                where id=?
                """, id);
            return new TestResult(true, capability, latency, null);
        } catch (Exception error) {
            String errorCode = "PROVIDER_TEST_" + error.getClass().getSimpleName()
                    .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
            jdbc.update("""
                update knowledge.provider_connection
                set last_test_status='FAIL',last_tested_at=now(),last_error_code=?,
                  status=case when status='ACTIVE' then 'DEGRADED' else status end,updated_at=now()
                where id=?
                """, errorCode, id);
            return new TestResult(false, capability, elapsedMillis(started), errorCode);
        }
    }

    @Transactional
    public ConnectionView assign(UUID id, AppUserPrincipal actor, String taskType, String modelName,
                                 Integer timeoutMs, Double inputPrice, Double outputPrice) {
        String task = taskType == null ? "" : taskType.strip().toUpperCase(Locale.ROOT);
        if (!CAPABILITIES.contains(task)) throw new IllegalArgumentException("不支持的任务类型");
        ConnectionView connection = findPlatform(id);
        if (!connection.capabilities().contains(task)) {
            throw new IllegalArgumentException("该连接未声明 " + task + " 能力");
        }
        if (!"PASS".equals(connection.lastTestStatus())) {
            throw new IllegalArgumentException("连接必须先通过真实测试");
        }
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("模型名称不能为空");
        int boundedTimeout = Math.max(1_000, Math.min(timeoutMs == null ? 40_000 : timeoutMs, 120_000));
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (inputPrice != null && inputPrice >= 0) parameters.put("inputCostPerMillion", inputPrice);
        if (outputPrice != null && outputPrice >= 0) parameters.put("outputCostPerMillion", outputPrice);
        String parameterJson;
        try {
            parameterJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(parameters);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        jdbc.update("""
            update knowledge.provider_config
            set connection_id=?,provider_name=?,model_name=?,base_url=?,credential_ref=?,
              status='ACTIVE',timeout_ms=?,parameters=?::jsonb,updated_by=?,updated_at=now()
            where task_type=?
            """, id, connection.vendor(), modelName.strip(), connection.baseUrl(),
                "db:" + id, boundedTimeout, parameterJson, actor.id(), task);
        jdbc.update("""
            update knowledge.provider_connection set status='ACTIVE',updated_by=?,updated_at=now()
            where id=?
            """, actor.id(), id);
        return findPlatform(id);
    }

    public RuntimeSelection selection(String taskType, UUID ownerUserId) {
        if (ownerUserId != null) {
            List<UserRoute> userRoutes = jdbc.query("""
                select connection.id,connection.scope,connection.vendor,connection.api_protocol,
                  connection.base_url,connection.credential_kind,connection.credential_ref,
                  connection.credential_ciphertext,connection.credential_nonce,connection.capabilities,
                  assignment.model_name,assignment.timeout_ms,assignment.parameters::text,
                  assignment.status assignment_status,connection.status connection_status,
                  assignment.platform_fallback_enabled
                from knowledge.user_provider_assignment assignment
                join knowledge.provider_connection connection on connection.id=assignment.connection_id
                where assignment.user_id=? and assignment.task_type=? and connection.scope='USER'
                  and connection.owner_user_id=assignment.user_id
                """, (rs, row) -> new UserRoute(new ConnectionSecret(
                    rs.getObject("id", UUID.class), rs.getString("scope"), rs.getString("vendor"),
                    rs.getString("api_protocol"), rs.getString("base_url"),
                    rs.getString("credential_kind"), rs.getString("credential_ref"),
                    rs.getString("credential_ciphertext"), rs.getString("credential_nonce"),
                    rs.getArray("capabilities") == null ? List.of()
                            : List.of((String[]) rs.getArray("capabilities").getArray()),
                    rs.getString("model_name"), rs.getInt("timeout_ms"),
                    rs.getString("parameters")),
                    rs.getString("assignment_status"), rs.getString("connection_status"),
                    rs.getBoolean("platform_fallback_enabled")), ownerUserId, taskType);
            if (!userRoutes.isEmpty()) {
                UserRoute route = userRoutes.get(0);
                if ("ACTIVE".equals(route.assignmentStatus()) && "ACTIVE".equals(route.connectionStatus())) {
                    return runtime(route.connection(), taskType);
                }
                if (!route.platformFallbackEnabled()) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "USER_PROVIDER_UNAVAILABLE",
                            "你的模型连接当前不可用；平台 Key 回落未开启");
                }
            } else if (ragRequireUserKey && "RAG_GENERATION".equals(taskType)) {
                throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "USER_PROVIDER_REQUIRED",
                        "当前站点要求先配置个人模型 API Key");
            }
        }
        return platformSelection(taskType);
    }

    private RuntimeSelection platformSelection(String taskType) {
        List<ConnectionSecret> rows = jdbc.query("""
            select connection.id,connection.scope,connection.vendor,connection.api_protocol,
              connection.base_url,connection.credential_kind,connection.credential_ref,
              connection.credential_ciphertext,connection.credential_nonce,connection.capabilities,
              config.model_name,config.timeout_ms,config.parameters::text
            from knowledge.provider_config config
            join knowledge.provider_connection connection on connection.id=config.connection_id
            where config.task_type=? and config.status='ACTIVE' and connection.status='ACTIVE'
              and connection.scope='PLATFORM'
            limit 1
            """, (rs, row) -> new ConnectionSecret(
                rs.getObject("id", UUID.class), rs.getString("scope"), rs.getString("vendor"),
                rs.getString("api_protocol"), rs.getString("base_url"),
                rs.getString("credential_kind"), rs.getString("credential_ref"),
                rs.getString("credential_ciphertext"), rs.getString("credential_nonce"),
                rs.getArray("capabilities") == null ? List.of()
                        : List.of((String[]) rs.getArray("capabilities").getArray()),
                rs.getString("model_name"), rs.getInt("timeout_ms"),
                rs.getString("parameters")), taskType);
        if (rows.isEmpty()) return RuntimeSelection.environment();
        return runtime(rows.get(0), taskType);
    }

    private RuntimeSelection runtime(ConnectionSecret connection, String taskType) {
        if ("ENVIRONMENT".equals(connection.credentialKind())) {
            return new RuntimeSelection(connection.id(), connection.scope(), connection.vendor(),
                    connection.modelName(), null, connection.parametersJson());
        }
        return new RuntimeSelection(connection.id(), connection.scope(), connection.vendor(),
                connection.modelName(), override(connection, taskType), connection.parametersJson());
    }

    private ConnectionView findPlatform(UUID id) {
        return platformConnections().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Provider 连接不存在"));
    }

    private ConnectionSecret secret(UUID id) {
        return jdbc.query("""
            select id,scope,vendor,api_protocol,base_url,credential_kind,credential_ref,
              credential_ciphertext,credential_nonce,capabilities,null::text model_name,
              40000 timeout_ms,'{}'::text parameters
            from knowledge.provider_connection where id=? and scope='PLATFORM'
            """, (rs, row) -> new ConnectionSecret(
                rs.getObject("id", UUID.class), rs.getString("scope"), rs.getString("vendor"),
                rs.getString("api_protocol"), rs.getString("base_url"),
                rs.getString("credential_kind"), rs.getString("credential_ref"),
                rs.getString("credential_ciphertext"), rs.getString("credential_nonce"),
                rs.getArray("capabilities") == null ? List.of()
                        : List.of((String[]) rs.getArray("capabilities").getArray()),
                rs.getString("model_name"), rs.getInt("timeout_ms"), rs.getString("parameters")), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Provider 连接不存在"));
    }

    private Map<String, Object> override(ConnectionSecret connection, String capability) {
        if (!"ENCRYPTED".equals(connection.credentialKind())) {
            throw new IllegalArgumentException("环境变量连接由 AI Runtime 管理，不能动态测试");
        }
        String model = connection.modelName();
        if (model == null || model.isBlank()) {
            model = defaultModel(connection.vendor(), capability);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_name", connection.vendor());
        result.put("api_protocol", connection.protocol());
        result.put("base_url", connection.baseUrl());
        result.put("api_key", cipher.decrypt(connection.ciphertext(), connection.nonce()));
        result.put("model", model);
        result.put("timeout_ms", connection.timeoutMs());
        result.put("retry_attempts", 1);
        return result;
    }

    private ValidatedConnection validate(SaveConnection request) {
        String displayName = request.displayName() == null ? "" : request.displayName().strip();
        String vendor = request.vendor() == null ? "" : request.vendor().strip().toLowerCase(Locale.ROOT);
        String protocol = request.apiProtocol() == null ? "OPENAI_COMPATIBLE"
                : request.apiProtocol().strip().toUpperCase(Locale.ROOT);
        String baseUrl = endpointPolicy.validate(request.baseUrl(), false);
        List<String> capabilities = request.capabilities() == null ? List.of()
                : request.capabilities().stream().map(value -> value.strip().toUpperCase(Locale.ROOT))
                    .distinct().toList();
        if (displayName.length() < 2 || displayName.length() > 80) {
            throw new IllegalArgumentException("连接名称长度必须为 2～80 个字符");
        }
        if (vendor.isBlank() || vendor.length() > 40) throw new IllegalArgumentException("厂商标识不能为空");
        if (!PROTOCOLS.contains(protocol)) throw new IllegalArgumentException("不支持的 API 协议");
        if (capabilities.isEmpty() || capabilities.stream().anyMatch(value -> !CAPABILITIES.contains(value))) {
            throw new IllegalArgumentException("至少选择一项有效能力");
        }
        return new ValidatedConnection(displayName, vendor, protocol, baseUrl, capabilities);
    }

    private static String defaultModel(String vendor, String capability) {
        if ("EMBEDDING".equals(capability)) return "BAAI/bge-m3";
        if ("RERANK".equals(capability)) return "BAAI/bge-reranker-v2-m3";
        if ("deepseek".equals(vendor)) return "deepseek-chat";
        if ("qwen".equals(vendor)) return "qwen-plus";
        if ("moonshot".equals(vendor)) return "moonshot-v1-8k";
        return "gpt-4.1-mini";
    }

    private static String lastFour(String key) {
        String stripped = key.strip();
        return stripped.substring(Math.max(0, stripped.length() - 4));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private record ValidatedConnection(String displayName, String vendor, String protocol,
                                       String baseUrl, List<String> capabilities) {}
    private record ConnectionSecret(UUID id, String scope, String vendor, String protocol,
                                    String baseUrl, String credentialKind, String credentialRef,
                                    String ciphertext, String nonce, List<String> capabilities,
                                    String modelName, int timeoutMs, String parametersJson) {}
    private record UserRoute(ConnectionSecret connection, String assignmentStatus,
                             String connectionStatus, boolean platformFallbackEnabled) {}

    public record SaveConnection(String displayName, String vendor, String apiProtocol,
                                 String baseUrl, String apiKey, List<String> capabilities) {}
    public record ConnectionView(UUID id, String scope, String displayName, String vendor,
                                 String apiProtocol, String baseUrl, String credentialKind,
                                 String credentialRef, String keyLastFour, List<String> capabilities,
                                 String status, String lastTestStatus, OffsetDateTime lastTestedAt,
                                 String lastErrorCode, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public String maskedCredential() {
            return "ENCRYPTED".equals(credentialKind)
                    ? "•••• •••• •••• " + (keyLastFour == null ? "••••" : keyLastFour)
                    : "环境变量：" + credentialRef;
        }
    }
    public record TestResult(boolean passed, String capability, long latencyMs, String errorCode) {}
    public record RuntimeSelection(UUID connectionId, String scope, String provider, String model,
                                   Map<String, Object> override, String parametersJson) {
        static RuntimeSelection environment() {
            return new RuntimeSelection(null, "ENVIRONMENT", null, null, null, "{}");
        }
        public boolean dynamic() { return override != null; }
    }
}
