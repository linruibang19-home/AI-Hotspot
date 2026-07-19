package com.aihotspot.core.agent;

import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.knowledge.ResearchService;
import com.aihotspot.core.subscription.SubscriptionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {
    private final JdbcTemplate jdbc; private final ResearchService research; private final SubscriptionService subscriptions;
    public AgentService(JdbcTemplate jdbc,ResearchService research,SubscriptionService subscriptions){this.jdbc=jdbc;this.research=research;this.subscriptions=subscriptions;}
    public List<Map<String,Object>> definitions(){return jdbc.queryForList("select id,code,name,description,risk_level,coalesce(array_to_json(allowed_tools),'[]'::json)::text allowed_tools,status from automation.agent_definition where status='ACTIVE' order by risk_level,code");}
    public List<Map<String,Object>> runs(UUID userId){return jdbc.queryForList("select r.id,d.code agent_code,d.name agent_name,r.objective,r.status,r.risk_level,r.result_data::text result_data,r.error_message,r.token_usage,r.started_at,r.completed_at,r.created_at from automation.agent_run r join automation.agent_definition d on d.id=r.agent_definition_id where r.user_id=? order by r.created_at desc limit 50",userId);}
    public List<Map<String,Object>> pendingApprovals(){return jdbc.queryForList("select a.id,a.agent_run_id,a.status,a.expires_at,a.created_at,r.objective,d.name agent_name,u.email::text requested_by_email from automation.approval_request a join automation.agent_run r on r.id=a.agent_run_id join automation.agent_definition d on d.id=r.agent_definition_id join iam.user_account u on u.id=a.requested_by where a.status='PENDING' and a.expires_at>now() order by a.created_at");}
    public Map<String,Object> detail(UUID userId,UUID runId){Map<String,Object> run=jdbc.queryForMap("select r.*,d.code agent_code,d.name agent_name from automation.agent_run r join automation.agent_definition d on d.id=r.agent_definition_id where r.id=? and r.user_id=?",runId,userId);run.put("steps",jdbc.queryForList("select * from automation.agent_step where agent_run_id=? order by step_no",runId));run.put("toolCalls",jdbc.queryForList("select id,agent_step_id,tool_code,risk_level,result_summary,status,created_at from automation.tool_call where agent_run_id=? order by created_at",runId));run.put("approval",jdbc.queryForList("select id,status,expires_at,decision_note,created_at,decided_at from automation.approval_request where agent_run_id=? order by created_at desc",runId));return run;}
    @Transactional
    public UUID start(AppUserPrincipal user,String code,String objective){
        Map<String,Object> definition=jdbc.queryForMap("select * from automation.agent_definition where code=? and status='ACTIVE'",code);UUID definitionId=(UUID)definition.get("id");String risk=String.valueOf(definition.get("risk_level"));UUID runId=UUID.randomUUID();UUID traceId=UUID.randomUUID();
        String status=List.of("L2","L3").contains(risk)?"WAITING_APPROVAL":"RUNNING";
        jdbc.update("insert into automation.agent_run(id,agent_definition_id,user_id,objective,status,risk_level,trace_id,started_at) values(?,?,?,?,?,?,?,case when ?='RUNNING' then now() end)",runId,definitionId,user.id(),objective,status,risk,traceId,status);
        UUID stepId=UUID.randomUUID();jdbc.update("insert into automation.agent_step(id,agent_run_id,step_no,step_type,title,status,started_at) values(?,?,1,'TOOL','执行受控工具',?,case when ?='RUNNING' then now() end)",stepId,runId,status.equals("RUNNING")?"RUNNING":"PENDING",status);
        String tool=firstTool(definition.get("allowed_tools"));UUID callId=UUID.randomUUID();String hash=hash(objective);
        jdbc.update("insert into automation.tool_call(id,agent_run_id,agent_step_id,tool_code,risk_level,arguments_hash,arguments,status) values(?,?,?,?,?,?,jsonb_build_object('objective',?),?)",callId,runId,stepId,tool,risk,hash,objective,status.equals("RUNNING")?"PENDING":"PENDING");
        if(status.equals("WAITING_APPROVAL")){jdbc.update("insert into automation.approval_request(id,agent_run_id,tool_call_id,requested_by,status,arguments_hash,expires_at) values(?,?,?,?,'PENDING',?,now()+interval '30 minutes')",UUID.randomUUID(),runId,callId,user.id(),hash);return runId;}
        execute(user,runId,code,objective,stepId,callId);return runId;
    }
    @Transactional public void decide(AppUserPrincipal approver,UUID approvalId,boolean approve,String note){
        Map<String,Object> request=jdbc.queryForMap("select a.*,r.user_id,d.code,r.objective from automation.approval_request a join automation.agent_run r on r.id=a.agent_run_id join automation.agent_definition d on d.id=r.agent_definition_id where a.id=? and a.status='PENDING' and a.expires_at>now()",approvalId);
        String status=approve?"APPROVED":"REJECTED";jdbc.update("update automation.approval_request set status=?,decided_by=?,decision_note=?,decided_at=now() where id=?",status,approver.id(),note,approvalId);UUID runId=(UUID)request.get("agent_run_id");
        if(!approve){jdbc.update("update automation.agent_run set status='CANCELLED',error_message='审批拒绝',completed_at=now() where id=?",runId);return;}
        UUID ownerId=(UUID)request.get("user_id");AppUserPrincipal owner=ownerId.equals(approver.id())?approver:approver;
        Map<String,Object> step=jdbc.queryForMap("select s.id step_id,t.id call_id from automation.agent_step s join automation.tool_call t on t.agent_step_id=s.id where s.agent_run_id=? order by s.step_no limit 1",runId);
        jdbc.update("update automation.agent_run set status='RUNNING',started_at=coalesce(started_at,now()) where id=?",runId);execute(owner,runId,String.valueOf(request.get("code")),String.valueOf(request.get("objective")),(UUID)step.get("step_id"),(UUID)step.get("call_id"));
    }
    @Transactional public void cancel(UUID userId,UUID runId){jdbc.update("update automation.agent_run set status='CANCELLED',completed_at=now() where id=? and user_id=? and status in ('QUEUED','WAITING_APPROVAL','RUNNING')",runId,userId);}
    private void execute(AppUserPrincipal user,UUID runId,String code,String objective,UUID stepId,UUID callId){try{
        Object result;if("RESEARCH".equals(code)){result=research.ask(user,null,objective,Map.of());}else if("SUBSCRIPTION".equals(code)){UUID id=subscriptions.create(user.id(),new SubscriptionService.SubscriptionRequest(objective,"DAILY","Asia/Shanghai",LocalTime.of(9,0),null,List.of(),12));result=Map.of("subscriptionId",id,"status","ACTIVE");}else{result=Map.of("diagnosis","已读取当前采集与信源健康状态；任何写操作仍需单独审批。","failedEndpoints",jdbc.queryForObject("select count(*) from source.source_endpoint where health_status='FAILED'",Long.class));}
        String json=json(result);jdbc.update("update automation.tool_call set status='SUCCEEDED',result_summary=? where id=?",json.substring(0,Math.min(1000,json.length())),callId);jdbc.update("update automation.agent_step set status='SUCCEEDED',result_summary=?,completed_at=now() where id=?",json.substring(0,Math.min(1000,json.length())),stepId);jdbc.update("update automation.agent_run set status='SUCCEEDED',result_data=?::jsonb,completed_at=now() where id=?",json,runId);
    }catch(Exception error){jdbc.update("update automation.tool_call set status='FAILED',result_summary=? where id=?",error.getMessage(),callId);jdbc.update("update automation.agent_step set status='FAILED',result_summary=?,completed_at=now() where id=?",error.getMessage(),stepId);jdbc.update("update automation.agent_run set status='FAILED',error_message=?,completed_at=now() where id=?",error.getMessage(),runId);}}
    private static String firstTool(Object tools){if(tools instanceof java.sql.Array array)try{Object[] values=(Object[])array.getArray();return values.length==0?"NONE":String.valueOf(values[0]);}catch(Exception ignored){}return "NONE";}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String json(Object value){try{return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);}catch(Exception e){return "{}";}}
}
