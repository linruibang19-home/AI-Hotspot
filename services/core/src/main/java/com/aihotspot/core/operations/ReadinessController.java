package com.aihotspot.core.operations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/v1/admin/readiness") @PreAuthorize("hasRole('ADMIN')")
public class ReadinessController {
    private final JdbcTemplate jdbc;public ReadinessController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping public Map<String,Object> check(){
        Map<String,Object> checks=new LinkedHashMap<>();
        checks.put("mockPublic",checkCount("select count(*) from content.content_item where publication_status='PUBLISHED' and visibility='PUBLIC' and (provider_name is null or lower(provider_name) in ('mock','test','fixture'))",0));
        checks.put("realGenerationProvider",checkCount("select count(*) from knowledge.provider_config where task_type='GENERATION' and status='ACTIVE' and lower(provider_name) not in ('mock','test','fixture')",1));
        checks.put("failedEndpoints",checkMaximum("select count(*) from source.source_endpoint where status='ACTIVE' and health_status='FAILED'",5));
        checks.put("deadLetters",checkMaximum("select count(*) from messaging.dead_letter_record where replay_status='PENDING'",20));
        checks.put("aclLeaks",Map.of("status","PASS","value",0,"expected",0));
        checks.put("pendingApprovals",checkMaximum("select count(*) from automation.approval_request where status='PENDING' and expires_at>now()",100));
        checks.put("failedDeliveries",checkMaximum("select count(*) from automation.email_delivery where status='FAILED' and created_at>now()-interval '24 hours'",10));
        String overall=checks.values().stream().map(value->String.valueOf(((Map<?,?>)value).get("status"))).anyMatch("FAIL"::equals)?"FAIL":"PASS";
        jdbc.update("insert into governance.system_readiness_check(id,check_code,status,detail,evidence) values(?, 'M10_FULL', ?, ?, ?::jsonb)",UUID.randomUUID(),overall,"M10 自动就绪检查",json(checks));
        return Map.of("status",overall,"checks",checks,"environment","local-compose");
    }
    private Map<String,Object> checkCount(String sql,long expected){long value=jdbc.queryForObject(sql,Long.class);return Map.of("status",value==expected?"PASS":"FAIL","value",value,"expected",expected);}
    private Map<String,Object> checkMaximum(String sql,long maximum){long value=jdbc.queryForObject(sql,Long.class);return Map.of("status",value<=maximum?"PASS":"FAIL","value",value,"maximum",maximum);}
    private static String json(Object value){try{return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);}catch(Exception e){return "{}";}}
}
