package com.aihotspot.core.knowledge;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.auth.AppUserPrincipal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class UserProviderConnectionService {
    private static final Set<String> TASKS = Set.of("RAG_GENERATION", "EMBEDDING", "RERANK");
    private static final Set<String> PROTOCOLS = Set.of("OPENAI_COMPATIBLE", "SILICONFLOW");
    private final JdbcTemplate jdbc;
    private final ProviderCredentialCipher cipher;
    private final ProviderEndpointPolicy endpointPolicy;
    private final RestClient ai;
    private final String internalToken;
    private final StringRedisTemplate redis;
    private final boolean ragRequireUserKey;

    public UserProviderConnectionService(
            JdbcTemplate jdbc,
            ProviderCredentialCipher cipher,
            ProviderEndpointPolicy endpointPolicy,
            StringRedisTemplate redis,
            @Value("${ai-hotspot.ai-base-url}") String aiBaseUrl,
            @Value("${ai-hotspot.security.ai-internal-token:ai-hotspot-local-internal-token}") String internalToken,
            @Value("${ai-hotspot.security.rag-require-user-key:false}") boolean ragRequireUserKey) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.endpointPolicy = endpointPolicy;
        this.redis = redis;
        this.internalToken = internalToken;
        this.ragRequireUserKey = ragRequireUserKey;
        this.ai = RestClient.builder().baseUrl(aiBaseUrl).build();
    }

    public List<UserConnectionView> connections(UUID userId) {
        return jdbc.query("""
            select connection.id,connection.display_name,connection.vendor,connection.api_protocol,
              connection.base_url,connection.key_last_four,connection.status,
              connection.last_test_status,connection.last_tested_at,connection.last_error_code,
              assignment.task_type,assignment.model_name,assignment.status assignment_status,
              assignment.timeout_ms,assignment.platform_fallback_enabled,
              assignment.daily_query_limit,assignment.monthly_token_limit,
              connection.created_at,connection.updated_at
            from knowledge.provider_connection connection
            join knowledge.user_provider_assignment assignment
              on assignment.connection_id=connection.id and assignment.user_id=connection.owner_user_id
            where connection.scope='USER' and connection.owner_user_id=?
            order by assignment.task_type
            """, (rs, row) -> new UserConnectionView(
                rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("vendor"), rs.getString("api_protocol"), rs.getString("base_url"),
                rs.getString("key_last_four"), rs.getString("status"),
                rs.getString("last_test_status"), rs.getObject("last_tested_at", OffsetDateTime.class),
                rs.getString("last_error_code"), rs.getString("task_type"), rs.getString("model_name"),
                rs.getString("assignment_status"), rs.getInt("timeout_ms"),
                rs.getBoolean("platform_fallback_enabled"), rs.getInt("daily_query_limit"),
                rs.getLong("monthly_token_limit"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), userId);
    }

    @Transactional
    public UserConnectionView create(AppUserPrincipal actor, SaveUserConnection request) {
        ValidatedRequest valid = validate(request, true);
        ProviderCredentialCipher.EncryptedValue encrypted = cipher.encrypt(request.apiKey());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into knowledge.provider_connection(
              id,owner_user_id,scope,display_name,vendor,api_protocol,base_url,credential_kind,
              credential_ciphertext,credential_nonce,key_fingerprint,key_last_four,
              capabilities,status,created_by,updated_by)
            values(?,?,'USER',?,?,?,?,'ENCRYPTED',?,?,?,?,?::text[],'DISABLED',?,?)
            """, id, actor.id(), valid.displayName(), valid.vendor(), valid.protocol(), valid.baseUrl(),
                encrypted.ciphertext(), encrypted.nonce(), cipher.fingerprint(request.apiKey()),
                lastFour(request.apiKey()), new String[]{valid.taskType()}, actor.id(), actor.id());
        jdbc.update("""
            insert into knowledge.user_provider_assignment(
              user_id,task_type,connection_id,model_name,status,timeout_ms,parameters,
              platform_fallback_enabled,daily_query_limit,monthly_token_limit)
            values(?,?,?,?, 'DISABLED',?,'{}'::jsonb,?,?,?)
            """, actor.id(), valid.taskType(), id, valid.modelName(), valid.timeoutMs(),
                valid.platformFallbackEnabled(), valid.dailyQueryLimit(), valid.monthlyTokenLimit());
        return owned(actor.id(), id);
    }

    @Transactional
    public UserConnectionView update(UUID userId, UUID id, SaveUserConnection request) {
        UserConnectionSecret current = secret(userId, id);
        ValidatedRequest valid = validate(request, false);
        if (!current.taskType().equals(valid.taskType())) {
            throw new ApiException(HttpStatus.CONFLICT, "BYOK_TASK_IMMUTABLE",
                    "任务类型不能修改；请撤销后重新创建");
        }
        boolean replaceKey = request.apiKey() != null && !request.apiKey().isBlank();
        if (replaceKey) {
            ProviderCredentialCipher.EncryptedValue encrypted = cipher.encrypt(request.apiKey());
            jdbc.update("""
                update knowledge.provider_connection
                set display_name=?,vendor=?,api_protocol=?,base_url=?,credential_ciphertext=?,
                  credential_nonce=?,key_fingerprint=?,key_last_four=?,status='DISABLED',
                  last_test_status='NOT_TESTED',last_error_code=null,updated_by=?,updated_at=now()
                where id=? and owner_user_id=? and scope='USER'
                """, valid.displayName(), valid.vendor(), valid.protocol(), valid.baseUrl(),
                    encrypted.ciphertext(), encrypted.nonce(), cipher.fingerprint(request.apiKey()),
                    lastFour(request.apiKey()), userId, id, userId);
        } else {
            jdbc.update("""
                update knowledge.provider_connection
                set display_name=?,vendor=?,api_protocol=?,base_url=?,status='DISABLED',
                  last_test_status='NOT_TESTED',last_error_code=null,updated_by=?,updated_at=now()
                where id=? and owner_user_id=? and scope='USER'
                """, valid.displayName(), valid.vendor(), valid.protocol(), valid.baseUrl(),
                    userId, id, userId);
        }
        jdbc.update("""
            update knowledge.user_provider_assignment
            set model_name=?,status='DISABLED',timeout_ms=?,platform_fallback_enabled=?,
              daily_query_limit=?,monthly_token_limit=?,updated_at=now()
            where user_id=? and connection_id=?
            """, valid.modelName(), valid.timeoutMs(), valid.platformFallbackEnabled(),
                valid.dailyQueryLimit(), valid.monthlyTokenLimit(), userId, id);
        return owned(userId, id);
    }

    @Transactional
    public TestResult test(UUID userId, UUID id) {
        UserConnectionSecret connection = secret(userId, id);
        long started = System.nanoTime();
        try {
            Map<String, Object> override = override(connection);
            Map<String, Object> response;
            if ("EMBEDDING".equals(connection.taskType())) {
                response = post("/api/v1/embed", Map.of(
                        "texts", List.of("AI Hotspot BYOK connection test"),
                        "provider_override", override));
                if (!(response.get("vectors") instanceof List<?> values) || values.isEmpty()) {
                    throw new IllegalStateException("Embedding 返回为空");
                }
            } else if ("RERANK".equals(connection.taskType())) {
                response = post("/api/v1/rerank", Map.of(
                        "query", "AI", "documents", List.of(Map.of("id", "test", "text", "AI Hotspot")),
                        "top_n", 1, "provider_override", override));
                if (!(response.get("results") instanceof List<?> values) || values.isEmpty()) {
                    throw new IllegalStateException("Rerank 返回为空");
                }
            } else {
                response = post("/api/v1/generate", Map.of(
                        "prompt", "只回答 OK", "max_tokens", 64, "provider_override", override));
                if (String.valueOf(response.getOrDefault("text", "")).isBlank()) {
                    throw new IllegalStateException("Generation 返回为空");
                }
            }
            jdbc.update("""
                update knowledge.provider_connection
                set last_test_status='PASS',last_tested_at=now(),last_error_code=null,updated_at=now()
                where id=? and owner_user_id=?
                """, id, userId);
            return new TestResult(true, connection.taskType(), elapsedMillis(started), null);
        } catch (Exception error) {
            String errorCode = safeErrorCode(error);
            jdbc.update("""
                update knowledge.provider_connection
                set last_test_status='FAIL',last_tested_at=now(),last_error_code=?,
                  status=case when status='ACTIVE' then 'DEGRADED' else status end,updated_at=now()
                where id=? and owner_user_id=?
                """, errorCode, id, userId);
            jdbc.update("""
                update knowledge.user_provider_assignment set status='DISABLED',updated_at=now()
                where user_id=? and connection_id=?
                """, userId, id);
            return new TestResult(false, connection.taskType(), elapsedMillis(started), errorCode);
        }
    }

    @Transactional
    public UserConnectionView setStatus(UUID userId, UUID id, String status) {
        String normalized = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DISABLED").contains(normalized)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BYOK_STATUS_INVALID",
                    "状态只允许 ACTIVE 或 DISABLED");
        }
        UserConnectionView connection = owned(userId, id);
        if ("ACTIVE".equals(normalized) && !"PASS".equals(connection.lastTestStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "BYOK_TEST_REQUIRED",
                    "连接必须先通过真实测试");
        }
        jdbc.update("""
            update knowledge.provider_connection set status=?,updated_by=?,updated_at=now()
            where id=? and owner_user_id=? and scope='USER'
            """, normalized, userId, id, userId);
        jdbc.update("""
            update knowledge.user_provider_assignment set status=?,updated_at=now()
            where user_id=? and connection_id=?
            """, normalized, userId, id);
        return owned(userId, id);
    }

    @Transactional
    public void revoke(UUID userId, UUID id) {
        int deleted = jdbc.update("""
            delete from knowledge.provider_connection
            where id=? and owner_user_id=? and scope='USER'
            """, id, userId);
        if (deleted != 1) throw notFound();
    }

    public UsageView usage(UUID userId) {
        Map<String, Object> counts = jdbc.queryForMap("""
            select
              (select count(*) from research.query_run
               where user_id=? and created_at >=
                 (date_trunc('day',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai')) today_queries,
              coalesce(sum(input_tokens+output_tokens) filter(
                where credential_scope='USER' and recorded_at >=
                  (date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai')),0) month_tokens,
              coalesce(sum(estimated_cost) filter(
                where credential_scope='USER' and recorded_at >=
                  (date_trunc('month',now() at time zone 'Asia/Shanghai') at time zone 'Asia/Shanghai')),0) month_cost,
              count(*) filter(where credential_scope='USER') user_provider_calls
            from knowledge.provider_metric where actor_user_id=?
            """, userId, userId);
        List<Map<String, Object>> policy = jdbc.queryForList("""
            select daily_query_limit,monthly_token_limit,platform_fallback_enabled,status
            from knowledge.user_provider_assignment
            where user_id=? and task_type='RAG_GENERATION'
            """, userId);
        int dailyLimit = policy.isEmpty() ? 50 : ((Number) policy.get(0).get("daily_query_limit")).intValue();
        long monthlyLimit = policy.isEmpty() ? 5_000_000L
                : ((Number) policy.get(0).get("monthly_token_limit")).longValue();
        return new UsageView(
                ((Number) counts.get("today_queries")).longValue(), dailyLimit,
                ((Number) counts.get("month_tokens")).longValue(), monthlyLimit,
                ((Number) counts.get("month_cost")).doubleValue(),
                ((Number) counts.get("user_provider_calls")).longValue(),
                !policy.isEmpty() && Boolean.TRUE.equals(policy.get(0).get("platform_fallback_enabled")),
                ragRequireUserKey);
    }

    public RagPermit acquireRagPermit(UUID userId) {
        List<Map<String, Object>> routes = jdbc.queryForList("""
            select assignment.status assignment_status,connection.status connection_status,
              assignment.daily_query_limit,assignment.monthly_token_limit,
              assignment.platform_fallback_enabled
            from knowledge.user_provider_assignment assignment
            join knowledge.provider_connection connection on connection.id=assignment.connection_id
            where assignment.user_id=? and assignment.task_type='RAG_GENERATION'
              and connection.owner_user_id=assignment.user_id
            """, userId);
        if (routes.isEmpty()) {
            if (ragRequireUserKey) {
                throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "USER_PROVIDER_REQUIRED",
                        "请先在“我的模型”中配置并启用回答生成 Key");
            }
            return RagPermit.noop();
        }
        Map<String, Object> route = routes.get(0);
        if (!"ACTIVE".equals(route.get("assignment_status"))
                || !"ACTIVE".equals(route.get("connection_status"))) {
            if (!ragRequireUserKey && Boolean.TRUE.equals(route.get("platform_fallback_enabled"))) {
                return RagPermit.noop();
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "USER_PROVIDER_UNAVAILABLE",
                    "个人回答模型尚未通过烟测并启用");
        }
        UsageView usage = usage(userId);
        if (usage.todayQueries() >= usage.dailyQueryLimit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BYOK_DAILY_QUOTA_EXCEEDED",
                    "今日 RAG 查询额度已用完");
        }
        if (usage.monthTokens() >= usage.monthlyTokenLimit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BYOK_MONTHLY_TOKEN_EXCEEDED",
                    "本月个人模型 Token 额度已用完");
        }
        String lockKey = "ai-hotspot:byok:rag-running:" + userId;
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofMinutes(3));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BYOK_CONCURRENT_QUERY",
                    "已有一个研究任务正在运行，请完成后再试");
        }
        return new RagPermit(redis, lockKey, lockToken);
    }

    private UserConnectionView owned(UUID userId, UUID id) {
        return connections(userId).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(UserProviderConnectionService::notFound);
    }

    private UserConnectionSecret secret(UUID userId, UUID id) {
        return jdbc.query("""
            select connection.id,connection.vendor,connection.api_protocol,connection.base_url,
              connection.credential_ciphertext,connection.credential_nonce,
              assignment.task_type,assignment.model_name,assignment.timeout_ms
            from knowledge.provider_connection connection
            join knowledge.user_provider_assignment assignment on assignment.connection_id=connection.id
            where connection.id=? and connection.owner_user_id=? and connection.scope='USER'
              and assignment.user_id=connection.owner_user_id
            """, (rs, row) -> new UserConnectionSecret(
                rs.getObject("id", UUID.class), rs.getString("vendor"),
                rs.getString("api_protocol"), rs.getString("base_url"),
                rs.getString("credential_ciphertext"), rs.getString("credential_nonce"),
                rs.getString("task_type"), rs.getString("model_name"),
                rs.getInt("timeout_ms")), id, userId).stream().findFirst().orElseThrow(
                    UserProviderConnectionService::notFound);
    }

    private ValidatedRequest validate(SaveUserConnection request, boolean requireKey) {
        if (request == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BYOK_REQUEST_INVALID", "连接信息不能为空");
        }
        if (requireKey && (request.apiKey() == null || request.apiKey().isBlank())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BYOK_KEY_REQUIRED", "API Key 不能为空");
        }
        String displayName = request.displayName() == null ? "" : request.displayName().strip();
        String vendor = request.vendor() == null ? "" : request.vendor().strip().toLowerCase(Locale.ROOT);
        String protocol = request.apiProtocol() == null ? "OPENAI_COMPATIBLE"
                : request.apiProtocol().strip().toUpperCase(Locale.ROOT);
        String taskType = request.taskType() == null ? "" : request.taskType().strip().toUpperCase(Locale.ROOT);
        String modelName = request.modelName() == null ? "" : request.modelName().strip();
        if (displayName.length() < 2 || displayName.length() > 80 || vendor.isBlank()
                || vendor.length() > 40 || modelName.isBlank() || modelName.length() > 120
                || !PROTOCOLS.contains(protocol) || !TASKS.contains(taskType)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BYOK_REQUEST_INVALID",
                    "连接名称、厂商、任务类型或模型名称无效");
        }
        String baseUrl;
        try {
            baseUrl = endpointPolicy.validate(request.baseUrl(), true);
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BYOK_ENDPOINT_BLOCKED",
                    "服务地址必须是可解析的公网 HTTPS URL");
        }
        int timeout = Math.max(1_000, Math.min(request.timeoutMs() == null ? 40_000 : request.timeoutMs(), 120_000));
        int dailyLimit = Math.max(1, Math.min(request.dailyQueryLimit() == null ? 50 : request.dailyQueryLimit(), 500));
        long monthlyLimit = Math.max(10_000L,
                Math.min(request.monthlyTokenLimit() == null ? 5_000_000L : request.monthlyTokenLimit(), 100_000_000L));
        return new ValidatedRequest(displayName, vendor, protocol, baseUrl, taskType, modelName,
                timeout, Boolean.TRUE.equals(request.platformFallbackEnabled()), dailyLimit, monthlyLimit);
    }

    private Map<String, Object> override(UserConnectionSecret connection) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_name", connection.vendor());
        result.put("api_protocol", connection.protocol());
        result.put("base_url", endpointPolicy.validate(connection.baseUrl(), true));
        result.put("api_key", cipher.decrypt(connection.ciphertext(), connection.nonce()));
        result.put("model", connection.modelName());
        result.put("timeout_ms", connection.timeoutMs());
        result.put("retry_attempts", 1);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        Map<String, Object> response = ai.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .header("X-AI-Internal-Token", internalToken).body(body).retrieve().body(Map.class);
        return response == null ? Map.of() : response;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BYOK_CONNECTION_NOT_FOUND",
                "模型连接不存在或无权访问");
    }

    private static String safeErrorCode(Exception error) {
        return "PROVIDER_TEST_" + error.getClass().getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String lastFour(String key) {
        String stripped = key.strip();
        return stripped.substring(Math.max(0, stripped.length() - 4));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private record ValidatedRequest(String displayName, String vendor, String protocol,
                                    String baseUrl, String taskType, String modelName, int timeoutMs,
                                    boolean platformFallbackEnabled, int dailyQueryLimit,
                                    long monthlyTokenLimit) {}
    private record UserConnectionSecret(UUID id, String vendor, String protocol, String baseUrl,
                                        String ciphertext, String nonce, String taskType,
                                        String modelName, int timeoutMs) {}

    public record SaveUserConnection(String displayName, String vendor, String apiProtocol,
                                     String baseUrl, String apiKey, String taskType, String modelName,
                                     Integer timeoutMs, Boolean platformFallbackEnabled,
                                     Integer dailyQueryLimit, Long monthlyTokenLimit) {}
    public record UserConnectionView(UUID id, String displayName, String vendor, String apiProtocol,
                                     String baseUrl, String keyLastFour, String status,
                                     String lastTestStatus, OffsetDateTime lastTestedAt,
                                     String lastErrorCode, String taskType, String modelName,
                                     String assignmentStatus, int timeoutMs,
                                     boolean platformFallbackEnabled, int dailyQueryLimit,
                                     long monthlyTokenLimit, OffsetDateTime createdAt,
                                     OffsetDateTime updatedAt) {}
    public record TestResult(boolean passed, String taskType, long latencyMs, String errorCode) {}
    public record UsageView(long todayQueries, int dailyQueryLimit, long monthTokens,
                            long monthlyTokenLimit, double monthEstimatedCost, long userProviderCalls,
                            boolean platformFallbackEnabled, boolean userKeyRequired) {}

    public static final class RagPermit implements AutoCloseable {
        private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
                "if redis.call('get',KEYS[1]) == ARGV[1] then "
                        + "return redis.call('del',KEYS[1]) else return 0 end",
                Long.class);
        private final StringRedisTemplate redis;
        private final String key;
        private final String token;
        private RagPermit(StringRedisTemplate redis, String key, String token) {
            this.redis = redis;
            this.key = key;
            this.token = token;
        }
        static RagPermit noop() { return new RagPermit(null, null, null); }
        @Override public void close() {
            if (redis != null) redis.execute(RELEASE_SCRIPT, Collections.singletonList(key), token);
        }
    }
}
