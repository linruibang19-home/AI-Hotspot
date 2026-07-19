package com.aihotspot.core.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {
    private final JdbcTemplate jdbc;public RetentionService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Scheduled(cron="${ai-hotspot.retention.cron:0 30 3 * * *}",zone="Asia/Shanghai") @Transactional
    public void apply(){
        jdbc.update("delete from iam.email_verification_code where created_at<now()-interval '7 days'");
        jdbc.update("delete from knowledge.provider_metric where recorded_at<now()-interval '180 days'");
        jdbc.update("update automation.tool_call set arguments='{}'::jsonb where created_at<now()-interval '90 days' and arguments<>'{}'::jsonb");
        jdbc.update("update automation.approval_request set status='EXPIRED' where status='PENDING' and expires_at<=now()");
        jdbc.update("""
            update messaging.dead_letter_record d set replay_status='IGNORED'
            from source.source_endpoint e
            where d.replay_status='PENDING'
              and e.id=(d.payload#>>'{payload,endpointId}')::uuid
              and (e.status='ARCHIVED' or e.last_success_at>d.last_failed_at)
            """);
    }
}
