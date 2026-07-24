package com.aihotspot.core.agent;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.auth.DatabaseUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgentService {
    private static final Set<String> ACTIVE_STATUSES =
            Set.of("QUEUED", "WAITING_APPROVAL", "RUNNING");
    private static final Set<String> RETRYABLE_STATUSES =
            Set.of("FAILED", "CANCELLED");
    private static final Set<String> HIGH_RISK = Set.of("L2", "L3");
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final DatabaseUserDetailsService users;
    private final AuditService audit;
    private final AgentToolExecutor toolExecutor;
    private final AgentLifecycleMaintenanceService lifecycleMaintenance;
    private final Executor agentExecutor;
    private final TransactionTemplate transactions;

    public AgentService(
            JdbcTemplate jdbc,
            DatabaseUserDetailsService users,
            AuditService audit,
            AgentToolExecutor toolExecutor,
            AgentLifecycleMaintenanceService lifecycleMaintenance,
            @Qualifier("agentTaskExecutor") Executor agentExecutor,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.toolExecutor = toolExecutor;
        this.lifecycleMaintenance = lifecycleMaintenance;
        this.agentExecutor = agentExecutor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<Map<String, Object>> definitions(AppUserPrincipal user) {
        boolean canOperate = hasAuthority(user, "agent:approve");
        return jdbc.queryForList("""
                select id,code,name,description,risk_level,
                  coalesce(array_to_json(allowed_tools),'[]'::json)::text allowed_tools,status
                from automation.agent_definition
                where status='ACTIVE' and (risk_level in ('L0','L1') or ?)
                order by risk_level,code
                """, canOperate);
    }

    public List<Map<String, Object>> runs(UUID userId) {
        return jdbc.queryForList("""
                select r.id,d.code agent_code,d.name agent_name,r.objective,r.status,r.risk_level,
                  r.result_data::text result_data,r.error_message,r.failure_code,r.token_usage,
                  r.estimated_cost,r.attempt_no,r.retry_of_run_id,r.cancel_requested_at,
                  r.started_at,r.completed_at,r.created_at,
                  exists(
                    select 1 from knowledge.provider_config pc
                    where pc.task_type='RAG_GENERATION'
                      and jsonb_exists(pc.parameters,'inputCostPerMillion')
                      and jsonb_exists(pc.parameters,'outputCostPerMillion')
                  ) cost_configured,
                  case when r.status in ('FAILED','CANCELLED') and r.attempt_no < 3
                    and not exists(
                      select 1 from automation.tool_call t
                      where t.agent_run_id=r.id and t.status='SUCCEEDED'
                    )
                    and not (
                      d.code='SUBSCRIPTION' and exists(
                        select 1 from automation.tool_call t
                        where t.agent_run_id=r.id and t.started_at is not null
                      )
                    )
                  then true else false end retryable
                from automation.agent_run r
                join automation.agent_definition d on d.id=r.agent_definition_id
                where r.user_id=?
                order by r.created_at desc limit 50
                """, userId);
    }

    public List<Map<String, Object>> pendingApprovals() {
        lifecycleMaintenance.maintain();
        return jdbc.queryForList("""
                select a.id,a.agent_run_id,a.status,a.expires_at,a.created_at,r.objective,
                  r.attempt_no,d.name agent_name,u.email::text requested_by_email
                from automation.approval_request a
                join automation.agent_run r on r.id=a.agent_run_id
                join automation.agent_definition d on d.id=r.agent_definition_id
                join iam.user_account u on u.id=a.requested_by
                where a.status='PENDING' and a.expires_at>now()
                order by a.created_at
                """);
    }

    public Map<String, Object> detail(UUID userId, UUID runId) {
        Map<String, Object> run = ownedRun(userId, runId);
        boolean succeededTool = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                  select 1 from automation.tool_call
                  where agent_run_id=? and status='SUCCEEDED'
                )
                """, Boolean.class, runId));
        boolean ambiguousSubscriptionWrite = "SUBSCRIPTION".equals(run.get("agent_code"))
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(
                      select 1 from automation.tool_call
                      where agent_run_id=? and started_at is not null
                    )
                    """, Boolean.class, runId));
        run.put("retryable", canRetry(
                text(run.get("status")),
                number(run.get("attempt_no")),
                succeededTool,
                ambiguousSubscriptionWrite));
        run.put("steps", jdbc.queryForList("""
                select id,step_no,step_type,title,status,result_summary,started_at,completed_at
                from automation.agent_step where agent_run_id=? order by step_no
                """, runId));
        run.put("toolCalls", jdbc.queryForList("""
                select id,agent_step_id,tool_code,risk_level,result_summary,status,
                  started_at,completed_at,created_at
                from automation.tool_call where agent_run_id=? order by created_at
                """, runId));
        run.put("approvals", jdbc.queryForList("""
                select id,status,expires_at,decision_note,created_at,decided_at
                from automation.approval_request where agent_run_id=? order by created_at desc
                """, runId));
        return run;
    }

    public UUID start(
            AppUserPrincipal sessionUser,
            String code,
            String objective,
            HttpServletRequest request) {
        AppUserPrincipal currentUser = currentPrincipal(sessionUser);
        UUID runId = prepare(currentUser, code, objective, null, 1);
        audit.record(
                currentUser.id(), "AGENT_RUN_STARTED", "AGENT_RUN", runId,
                null, json(Map.of("agentCode", code, "attemptNo", 1)), request);
        dispatch(currentUser, runId);
        return runId;
    }

    public UUID retry(
            AppUserPrincipal sessionUser,
            UUID previousRunId,
            HttpServletRequest request) {
        AppUserPrincipal currentUser = currentPrincipal(sessionUser);
        Map<String, Object> previous = ownedRun(currentUser.id(), previousRunId);
        String status = text(previous.get("status"));
        int attempt = number(previous.get("attempt_no"));
        boolean succeededTool = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                  select 1 from automation.tool_call
                  where agent_run_id=? and status='SUCCEEDED'
                )
                """, Boolean.class, previousRunId));
        boolean subscriptionStarted = "SUBSCRIPTION".equals(previous.get("agent_code"))
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(
                      select 1 from automation.tool_call
                      where agent_run_id=? and started_at is not null
                    )
                    """, Boolean.class, previousRunId));
        if (!canRetry(status, attempt, succeededTool, subscriptionStarted)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AGENT_RUN_NOT_RETRYABLE",
                    "该运行已成功、达到重试上限或包含状态不确定的写操作，不能安全重试");
        }
        UUID runId = prepare(
                currentUser,
                text(previous.get("agent_code")),
                text(previous.get("objective")),
                previousRunId,
                attempt + 1);
        audit.record(
                currentUser.id(), "AGENT_RUN_RETRIED", "AGENT_RUN", runId,
                json(Map.of("retryOfRunId", previousRunId)),
                json(Map.of("attemptNo", attempt + 1)), request);
        dispatch(currentUser, runId);
        return runId;
    }

    public void cancel(
            AppUserPrincipal sessionUser,
            UUID runId,
            HttpServletRequest request) {
        AppUserPrincipal currentUser = currentPrincipal(sessionUser);
        Map<String, Object> run = ownedRun(currentUser.id(), runId);
        String status = text(run.get("status"));
        if (!ACTIVE_STATUSES.contains(status)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "AGENT_RUN_ALREADY_TERMINAL", "运行已经结束，不能重复取消");
        }
        transactions.executeWithoutResult(ignored -> {
            int updated = jdbc.update("""
                    update automation.agent_run
                    set status='CANCELLED',failure_code='USER_CANCELLED',
                      error_message='用户请求取消',cancel_requested_at=now(),completed_at=now()
                    where id=? and user_id=? and status in ('QUEUED','WAITING_APPROVAL','RUNNING')
                    """, runId, currentUser.id());
            if (updated == 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "AGENT_RUN_STATE_CHANGED", "运行状态已经变化，请刷新");
            }
            jdbc.update("""
                    update automation.approval_request
                    set status='REJECTED',decided_by=?,decision_note='发起人取消运行',
                      decided_at=now()
                    where agent_run_id=? and status='PENDING'
                    """, currentUser.id(), runId);
            jdbc.update("""
                    update automation.tool_call
                    set status=case when status='PENDING' then 'DENIED' else 'FAILED' end,
                      result_summary=case when status='PENDING' then '运行已在工具开始前取消'
                        else '运行取消时工具执行结果不确定，禁止自动重试' end,
                      completed_at=now()
                    where agent_run_id=? and status in ('PENDING','RUNNING')
                    """, runId);
            jdbc.update("""
                    update automation.agent_step
                    set status=case when status='PENDING' then 'SKIPPED' else 'FAILED' end,
                      result_summary=case when status='PENDING' then '运行已取消'
                        else '运行取消时步骤执行结果不确定' end,
                      completed_at=now()
                    where agent_run_id=? and status in ('PENDING','RUNNING')
                    """, runId);
        });
        audit.record(
                currentUser.id(), "AGENT_RUN_CANCELLED", "AGENT_RUN", runId,
                json(Map.of("status", status)),
                json(Map.of("status", "CANCELLED")), request);
    }

    public void decide(
            AppUserPrincipal approver,
            UUID approvalId,
            boolean approve,
            String note,
            HttpServletRequest request) {
        AppUserPrincipal currentApprover = currentPrincipal(approver);
        if (!hasAuthority(currentApprover, "agent:approve")) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AGENT_APPROVAL_PERMISSION_REVOKED",
                    "当前账号已无 Agent 审批权限");
        }
        lifecycleMaintenance.maintain();
        Map<String, Object> approval = approval(approvalId);
        if (!"PENDING".equals(approval.get("status"))) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "AGENT_APPROVAL_NOT_PENDING", "审批已处理或过期");
        }
        if (!Boolean.TRUE.equals(approval.get("not_expired"))) {
            lifecycleMaintenance.maintain();
            throw new ApiException(
                    HttpStatus.CONFLICT, "AGENT_APPROVAL_EXPIRED", "审批已过期，请重新运行任务");
        }
        String expectedHash = hash(text(approval.get("objective")));
        if (!approvalHashMatches(
                text(approval.get("approval_hash")),
                text(approval.get("tool_hash")),
                expectedHash)) {
            rejectInvalidApproval(approval, currentApprover.id(), "审批参数已变化");
            throw new ApiException(
                    HttpStatus.CONFLICT, "AGENT_APPROVAL_ARGUMENTS_CHANGED",
                    "工具参数已变化，旧审批已失效");
        }

        UUID runId = (UUID) approval.get("agent_run_id");
        if (!approve) {
            rejectInvalidApproval(approval, currentApprover.id(), normalizeNote(note, "审批拒绝"));
            audit.record(
                    currentApprover.id(), "AGENT_APPROVAL_REJECTED", "AGENT_RUN", runId,
                    null, json(Map.of("approvalId", approvalId)), request);
            return;
        }

        AppUserPrincipal owner;
        try {
            owner = loadOwner((UUID) approval.get("user_id"));
            requireCapabilities(owner, text(approval.get("risk_level")));
        } catch (RuntimeException exception) {
            rejectInvalidApproval(approval, currentApprover.id(), "发起人当前权限不足或账号不可用");
            audit.record(
                    currentApprover.id(), "AGENT_APPROVAL_PERMISSION_REVOKED", "AGENT_RUN", runId,
                    null, json(Map.of("approvalId", approvalId)), request);
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "AGENT_OWNER_PERMISSION_REVOKED",
                    "发起人的当前权限不足，审批已失效");
        }

        transactions.executeWithoutResult(ignored -> {
            int updated = jdbc.update("""
                    update automation.approval_request
                    set status='APPROVED',decided_by=?,decision_note=?,decided_at=now()
                    where id=? and status='PENDING' and expires_at>now()
                    """, currentApprover.id(), normalizeNote(note, "审批通过"), approvalId);
            if (updated == 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "AGENT_APPROVAL_STATE_CHANGED", "审批状态已经变化");
            }
            int runUpdated = jdbc.update("""
                    update automation.agent_run
                    set status='RUNNING',started_at=coalesce(started_at,now()),
                      last_heartbeat_at=now()
                    where id=? and status='WAITING_APPROVAL'
                    """, runId);
            if (runUpdated == 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "AGENT_RUN_STATE_CHANGED", "运行状态已经变化，请刷新");
            }
            jdbc.update("""
                    update automation.agent_step
                    set status='RUNNING',started_at=coalesce(started_at,now())
                    where agent_run_id=? and status='PENDING'
                    """, runId);
        });
        audit.record(
                currentApprover.id(), "AGENT_APPROVAL_APPROVED", "AGENT_RUN", runId,
                null, json(Map.of("approvalId", approvalId)), request);
        dispatch(owner, runId);
    }

    private UUID prepare(
            AppUserPrincipal user,
            String codeValue,
            String objectiveValue,
            UUID retryOf,
            int attemptNo) {
        String code = codeValue == null ? "" : codeValue.strip().toUpperCase();
        String objective = objectiveValue == null ? "" : objectiveValue.strip();
        if (objective.length() < 3 || objective.length() > 1000) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_AGENT_OBJECTIVE",
                    "Agent 任务目标长度必须为 3～1000 个字符");
        }
        Map<String, Object> definition = definition(code);
        String risk = text(definition.get("risk_level"));
        requireCapabilities(user, risk);
        UUID runId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        String status = HIGH_RISK.contains(risk) ? "WAITING_APPROVAL" : "RUNNING";
        String tool = firstTool(definition.get("allowed_tools"));
        String argumentsHash = hash(objective);
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    insert into automation.agent_run(
                      id,agent_definition_id,user_id,objective,status,risk_level,trace_id,
                      retry_of_run_id,attempt_no,permission_snapshot,started_at,last_heartbeat_at
                    ) values(?,?,?,?,?,?,?,?,?,?::jsonb,
                      case when ?='RUNNING' then now() end,
                      case when ?='RUNNING' then now() end)
                    """, runId, definition.get("id"), user.id(), objective, status, risk, traceId,
                    retryOf, attemptNo, permissionSnapshot(user), status, status);
            UUID stepId = UUID.randomUUID();
            jdbc.update("""
                    insert into automation.agent_step(
                      id,agent_run_id,step_no,step_type,title,status,started_at
                    ) values(?,?,1,'TOOL','执行受控工具',?,
                      case when ?='RUNNING' then now() end)
                    """, stepId, runId, "RUNNING".equals(status) ? "RUNNING" : "PENDING", status);
            UUID callId = UUID.randomUUID();
            jdbc.update("""
                    insert into automation.tool_call(
                      id,agent_run_id,agent_step_id,tool_code,risk_level,
                      arguments_hash,arguments,status
                    ) values(?,?,?,?,?,?,jsonb_build_object('objective',?),'PENDING')
                    """, callId, runId, stepId, tool, risk, argumentsHash, objective);
            if ("WAITING_APPROVAL".equals(status)) {
                jdbc.update("""
                        insert into automation.approval_request(
                          id,agent_run_id,tool_call_id,requested_by,status,
                          arguments_hash,expires_at
                        ) values(?,?,?,?,'PENDING',?,now()+interval '30 minutes')
                        """, UUID.randomUUID(), runId, callId, user.id(), argumentsHash);
            }
        });
        return runId;
    }

    private void executeIfReady(AppUserPrincipal user, UUID runId) {
        Map<String, Object> context = executionContext(runId);
        if (!"RUNNING".equals(context.get("status"))) {
            return;
        }
        AppUserPrincipal current = currentPrincipal(user);
        requireCapabilities(current, text(context.get("risk_level")));
        int claimed = transactions.execute(ignored -> {
            int updated = jdbc.update("""
                    update automation.tool_call
                    set status='RUNNING',started_at=coalesce(started_at,now())
                    where id=? and status='PENDING'
                      and exists(
                        select 1 from automation.agent_run
                        where id=? and status='RUNNING'
                      )
                    """, context.get("call_id"), runId);
            if (updated == 1) {
                jdbc.update("""
                        update automation.agent_run set last_heartbeat_at=now()
                        where id=? and status='RUNNING'
                        """, runId);
            }
            return updated;
        });
        if (claimed != 1) {
            return;
        }

        try {
            AgentToolExecutor.ExecutionResult result = toolExecutor.execute(
                    current, text(context.get("agent_code")), text(context.get("objective")));
            String resultJson = json(result.value());
            Integer completed = transactions.execute(ignored -> {
                jdbc.update("""
                        update automation.tool_call
                        set status='SUCCEEDED',result_summary=?,completed_at=now()
                        where id=? and status='RUNNING'
                        """, abbreviate(resultJson), context.get("call_id"));
                jdbc.update("""
                        update automation.agent_step
                        set status='SUCCEEDED',result_summary=?,completed_at=now()
                        where id=? and status='RUNNING'
                        """, abbreviate(resultJson), context.get("step_id"));
                return jdbc.update("""
                        update automation.agent_run
                        set status='SUCCEEDED',result_data=?::jsonb,token_usage=?,
                          estimated_cost=?,last_heartbeat_at=now(),completed_at=now()
                        where id=? and status='RUNNING'
                        """, resultJson, result.tokenUsage(), result.estimatedCost(), runId);
            });
            if (Integer.valueOf(1).equals(completed)) {
                audit.record(
                        current.id(), "AGENT_RUN_SUCCEEDED", "AGENT_RUN", runId,
                        null, json(Map.of("tokenUsage", result.tokenUsage())), null);
            }
        } catch (Exception error) {
            String message = safeError(error);
            Integer failed = transactions.execute(ignored -> {
                jdbc.update("""
                        update automation.tool_call
                        set status='FAILED',result_summary=?,completed_at=now()
                        where id=? and status='RUNNING'
                        """, message, context.get("call_id"));
                jdbc.update("""
                        update automation.agent_step
                        set status='FAILED',result_summary=?,completed_at=now()
                        where id=? and status='RUNNING'
                        """, message, context.get("step_id"));
                return jdbc.update("""
                        update automation.agent_run
                        set status='FAILED',failure_code='TOOL_EXECUTION_FAILED',
                          error_message=?,last_heartbeat_at=now(),completed_at=now()
                        where id=? and status='RUNNING'
                        """, message, runId);
            });
            if (Integer.valueOf(1).equals(failed)) {
                audit.record(
                        current.id(), "AGENT_RUN_FAILED", "AGENT_RUN", runId,
                        null, json(Map.of("failureCode", "TOOL_EXECUTION_FAILED")), null);
            }
        }
    }

    private void dispatch(AppUserPrincipal user, UUID runId) {
        Map<String, Object> run = executionContext(runId);
        if (!"RUNNING".equals(run.get("status"))) {
            return;
        }
        try {
            agentExecutor.execute(() -> {
                try {
                    executeIfReady(user, runId);
                } catch (Exception error) {
                    failBeforeToolCompletion(runId, error);
                }
            });
        } catch (RejectedExecutionException error) {
            failBeforeToolCompletion(runId, error);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_EXECUTOR_SATURATED",
                    "Agent 执行队列已满，请稍后重试");
        }
    }

    private void failBeforeToolCompletion(UUID runId, Exception error) {
        String message = safeError(error);
        Integer failed = transactions.execute(ignored -> {
            jdbc.update("""
                    update automation.tool_call
                    set status='FAILED',result_summary=?,completed_at=now()
                    where agent_run_id=? and status in ('PENDING','RUNNING')
                    """, message, runId);
            jdbc.update("""
                    update automation.agent_step
                    set status='FAILED',result_summary=?,completed_at=now()
                    where agent_run_id=? and status in ('PENDING','RUNNING')
                    """, message, runId);
            return jdbc.update("""
                    update automation.agent_run
                    set status='FAILED',failure_code='EXECUTION_DISPATCH_FAILED',
                      error_message=?,completed_at=now()
                    where id=? and status='RUNNING'
                    """, message, runId);
        });
        if (Integer.valueOf(1).equals(failed)) {
            audit.record(
                    null, "AGENT_RUN_DISPATCH_FAILED", "AGENT_RUN", runId,
                    null, json(Map.of("failureCode", "EXECUTION_DISPATCH_FAILED")), null);
        }
    }

    private void rejectInvalidApproval(
            Map<String, Object> approval,
            UUID approverId,
            String note) {
        UUID runId = (UUID) approval.get("agent_run_id");
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    update automation.approval_request
                    set status='REJECTED',decided_by=?,decision_note=?,decided_at=now()
                    where id=? and status='PENDING'
                    """, approverId, note, approval.get("id"));
            jdbc.update("""
                    update automation.agent_run
                    set status='CANCELLED',failure_code='APPROVAL_REJECTED',
                      error_message=?,completed_at=now()
                    where id=? and status='WAITING_APPROVAL'
                    """, note, runId);
            jdbc.update("""
                    update automation.agent_step
                    set status='SKIPPED',result_summary=?,completed_at=now()
                    where agent_run_id=? and status='PENDING'
                    """, note, runId);
            jdbc.update("""
                    update automation.tool_call
                    set status='DENIED',result_summary=?,completed_at=now()
                    where agent_run_id=? and status='PENDING'
                    """, note, runId);
        });
    }

    private Map<String, Object> definition(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from automation.agent_definition
                where code=? and status='ACTIVE'
                """, code);
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "AGENT_DEFINITION_NOT_FOUND", "Agent 不存在或已停用");
        }
        return rows.get(0);
    }

    private Map<String, Object> ownedRun(UUID userId, UUID runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.*,d.code agent_code,d.name agent_name,
                  exists(
                    select 1 from knowledge.provider_config pc
                    where pc.task_type='RAG_GENERATION'
                      and jsonb_exists(pc.parameters,'inputCostPerMillion')
                      and jsonb_exists(pc.parameters,'outputCostPerMillion')
                  ) cost_configured
                from automation.agent_run r
                join automation.agent_definition d on d.id=r.agent_definition_id
                where r.id=? and r.user_id=?
                """, runId, userId);
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent 运行不存在或不可访问");
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    private Map<String, Object> approval(UUID approvalId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.id,a.agent_run_id,a.status,a.expires_at,
                  (a.expires_at>now()) not_expired,a.arguments_hash approval_hash,
                  t.arguments_hash tool_hash,r.user_id,r.objective,r.status run_status,
                  r.risk_level,d.code agent_code
                from automation.approval_request a
                join automation.agent_run r on r.id=a.agent_run_id
                join automation.agent_definition d on d.id=r.agent_definition_id
                join automation.tool_call t on t.id=a.tool_call_id
                where a.id=?
                """, approvalId);
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "AGENT_APPROVAL_NOT_FOUND", "审批不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> executionContext(UUID runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id,r.status,r.objective,r.risk_level,d.code agent_code,
                  s.id step_id,t.id call_id
                from automation.agent_run r
                join automation.agent_definition d on d.id=r.agent_definition_id
                join automation.agent_step s on s.agent_run_id=r.id
                join automation.tool_call t on t.agent_step_id=s.id
                where r.id=? order by s.step_no limit 1
                """, runId);
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent 运行不存在");
        }
        return rows.get(0);
    }

    private AppUserPrincipal currentPrincipal(AppUserPrincipal sessionUser) {
        return users.loadActivePrincipal(sessionUser.getUsername());
    }

    private AppUserPrincipal loadOwner(UUID userId) {
        String email = jdbc.queryForObject(
                "select email::text from iam.user_account where id=?",
                String.class,
                userId);
        return users.loadActivePrincipal(email);
    }

    private static void requireCapabilities(AppUserPrincipal user, String risk) {
        if (!hasAuthority(user, "agent:use")) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "AGENT_PERMISSION_REVOKED", "当前账号已无 Agent 使用权限");
        }
        if (HIGH_RISK.contains(risk) && !hasAuthority(user, "agent:approve")) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AGENT_HIGH_RISK_PERMISSION_REQUIRED",
                    "高风险 Agent 只允许运营或管理员发起");
        }
    }

    static boolean canRetry(
            String status,
            int attemptNo,
            boolean succeededTool,
            boolean ambiguousSubscriptionWrite) {
        return RETRYABLE_STATUSES.contains(status)
                && attemptNo < MAX_ATTEMPTS
                && !succeededTool
                && !ambiguousSubscriptionWrite;
    }

    static boolean approvalHashMatches(
            String approvalHash,
            String toolHash,
            String expectedHash) {
        return !expectedHash.isBlank()
                && expectedHash.equals(approvalHash)
                && expectedHash.equals(toolHash);
    }

    private static boolean hasAuthority(AppUserPrincipal user, String authority) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private static String permissionSnapshot(AppUserPrincipal user) {
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
        return json(Map.of(
                "userId", user.id(),
                "roles", user.roles(),
                "authorities", authorities.stream().map(GrantedAuthority::getAuthority).toList()));
    }

    private static String firstTool(Object tools) {
        if (tools instanceof java.sql.Array array) {
            try {
                Object[] values = (Object[]) array.getArray();
                return values.length == 0 ? "NONE" : String.valueOf(values[0]);
            } catch (Exception ignored) {
                return "NONE";
            }
        }
        return "NONE";
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String normalizeNote(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }

    private static String safeError(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) {
            value = error.getClass().getSimpleName();
        }
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private static String abbreviate(String value) {
        return value.substring(0, Math.min(1000, value.length()));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String json(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
