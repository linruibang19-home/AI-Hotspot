package com.aihotspot.core.agent;

import com.aihotspot.core.audit.AuditService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentLifecycleMaintenanceService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public AgentLifecycleMaintenanceService(
            JdbcTemplate jdbc,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            fixedDelayString = "${ai-hotspot.agent.lifecycle-fixed-delay:15000}",
            initialDelayString = "${ai-hotspot.agent.lifecycle-initial-delay:5000}")
    public int maintain() {
        Integer maintained = transactions.execute(ignored -> maintainInTransaction());
        return maintained == null ? 0 : maintained;
    }

    private int maintainInTransaction() {
        Integer agentRunTableCount = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where lower(table_schema)='automation'
                  and lower(table_name)='agent_run'
                """, Integer.class);
        if (agentRunTableCount == null || agentRunTableCount == 0) {
            return 0;
        }

        List<UUID> expired = jdbc.queryForList("""
                select agent_run_id from automation.approval_request
                where status='PENDING' and expires_at<=now()
                for update skip locked
                """, UUID.class);
        jdbc.update("""
                update automation.approval_request
                set status='EXPIRED',decision_note='审批已过期',decided_at=now()
                where status='PENDING' and expires_at<=now()
                """);
        jdbc.update("""
                update automation.agent_run r
                set status='CANCELLED',failure_code='APPROVAL_EXPIRED',
                  error_message='审批已过期',completed_at=now()
                where r.status='WAITING_APPROVAL' and exists(
                  select 1 from automation.approval_request a
                  where a.agent_run_id=r.id and a.status='EXPIRED'
                )
                """);
        jdbc.update("""
                update automation.agent_step s
                set status='SKIPPED',result_summary='审批已过期',completed_at=now()
                from automation.agent_run r
                where s.agent_run_id=r.id and r.failure_code='APPROVAL_EXPIRED'
                  and s.status='PENDING'
                """);
        jdbc.update("""
                update automation.tool_call t
                set status='DENIED',result_summary='审批已过期',completed_at=now()
                from automation.agent_run r
                where t.agent_run_id=r.id and r.failure_code='APPROVAL_EXPIRED'
                  and t.status='PENDING'
                """);

        List<UUID> interrupted = jdbc.queryForList("""
                select id from automation.agent_run
                where status='RUNNING'
                  and coalesce(last_heartbeat_at,started_at,created_at)
                    <now()-interval '5 minutes'
                for update skip locked
                """, UUID.class);
        jdbc.update("""
                update automation.agent_run
                set status='FAILED',failure_code='EXECUTION_INTERRUPTED',
                  error_message='执行进程中断，可从原始输入创建安全重试',
                  completed_at=now()
                where status='RUNNING'
                  and coalesce(last_heartbeat_at,started_at,created_at)
                    <now()-interval '5 minutes'
                """);
        jdbc.update("""
                update automation.agent_step s
                set status='FAILED',result_summary='执行进程中断',completed_at=now()
                from automation.agent_run r
                where s.agent_run_id=r.id and r.failure_code='EXECUTION_INTERRUPTED'
                  and s.status='RUNNING'
                """);
        jdbc.update("""
                update automation.tool_call t
                set status='FAILED',result_summary='执行进程中断，工具结果可能不确定',
                  completed_at=now()
                from automation.agent_run r
                where t.agent_run_id=r.id and r.failure_code='EXECUTION_INTERRUPTED'
                  and t.status='RUNNING'
                """);
        expired.forEach(runId -> audit.record(
                null, "AGENT_APPROVAL_EXPIRED", "AGENT_RUN", runId, null, null, null));
        interrupted.forEach(runId -> audit.record(
                null, "AGENT_RUN_INTERRUPTED", "AGENT_RUN", runId, null, null, null));
        return expired.size() + interrupted.size();
    }
}
