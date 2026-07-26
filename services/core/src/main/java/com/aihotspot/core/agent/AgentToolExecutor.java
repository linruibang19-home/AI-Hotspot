package com.aihotspot.core.agent;

import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.knowledge.ResearchService;
import com.aihotspot.core.subscription.SubscriptionService;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentToolExecutor {
    private final JdbcTemplate jdbc;
    private final ResearchService research;
    private final SubscriptionService subscriptions;

    public AgentToolExecutor(
            JdbcTemplate jdbc,
            ResearchService research,
            SubscriptionService subscriptions) {
        this.jdbc = jdbc;
        this.research = research;
        this.subscriptions = subscriptions;
    }

    public ExecutionResult execute(
            AppUserPrincipal user,
            String agentCode,
            String objective) {
        if ("RESEARCH".equals(agentCode)) {
            ResearchService.ResearchResult result =
                    research.ask(user, null, objective, Map.of());
            return new ExecutionResult(
                    result,
                    result.inputTokens() + result.outputTokens(),
                    result.estimatedCost());
        }
        if ("SUBSCRIPTION".equals(agentCode)) {
            UUID id = subscriptions.create(
                    user.id(),
                    new SubscriptionService.SubscriptionRequest(
                            objective,
                            "DAILY",
                            "Asia/Shanghai",
                            LocalTime.of(9, 0),
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            12));
            return new ExecutionResult(
                    Map.of("subscriptionId", id, "status", "ACTIVE"),
                    0,
                    BigDecimal.ZERO);
        }
        return new ExecutionResult(
                Map.of(
                        "diagnosis",
                        "已读取当前采集与信源健康状态；任何写操作仍需单独审批。",
                        "failedEndpoints",
                        jdbc.queryForObject("""
                                select count(*) from source.source_endpoint
                                where health_status='FAILED'
                                """, Long.class)),
                0,
                BigDecimal.ZERO);
    }

    public record ExecutionResult(
            Object value,
            int tokenUsage,
            BigDecimal estimatedCost) {}
}
