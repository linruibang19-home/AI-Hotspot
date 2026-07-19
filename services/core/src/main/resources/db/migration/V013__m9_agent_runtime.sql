create table automation.agent_definition (
    id uuid primary key,
    code text not null unique,
    name text not null,
    description text not null,
    risk_level text not null,
    allowed_tools text[] not null default '{}',
    status text not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_agent_risk check (risk_level in ('L0','L1','L2','L3')),
    constraint ck_agent_status check (status in ('ACTIVE','DISABLED'))
);
create table automation.agent_run (
    id uuid primary key,
    agent_definition_id uuid not null references automation.agent_definition(id),
    user_id uuid not null references iam.user_account(id) on delete cascade,
    objective text not null,
    status text not null default 'QUEUED',
    risk_level text not null,
    input_data jsonb not null default '{}'::jsonb,
    result_data jsonb not null default '{}'::jsonb,
    error_message text,
    trace_id uuid not null,
    token_usage integer not null default 0,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint ck_agent_run_status check (status in ('QUEUED','WAITING_APPROVAL','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    constraint ck_agent_run_risk check (risk_level in ('L0','L1','L2','L3'))
);
create index ix_agent_run_user_recent on automation.agent_run(user_id,created_at desc);
create table automation.agent_step (
    id uuid primary key,
    agent_run_id uuid not null references automation.agent_run(id) on delete cascade,
    step_no integer not null,
    step_type text not null,
    title text not null,
    status text not null,
    input_hash text,
    result_summary text,
    started_at timestamptz,
    completed_at timestamptz,
    unique(agent_run_id,step_no),
    constraint ck_agent_step_status check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED'))
);
create table automation.tool_call (
    id uuid primary key,
    agent_run_id uuid not null references automation.agent_run(id) on delete cascade,
    agent_step_id uuid not null references automation.agent_step(id) on delete cascade,
    tool_code text not null,
    risk_level text not null,
    arguments_hash text not null,
    arguments jsonb not null default '{}'::jsonb,
    result_summary text,
    status text not null,
    created_at timestamptz not null default now(),
    constraint ck_tool_call_status check (status in ('PENDING','SUCCEEDED','FAILED','DENIED'))
);
create table automation.approval_request (
    id uuid primary key,
    agent_run_id uuid not null references automation.agent_run(id) on delete cascade,
    tool_call_id uuid references automation.tool_call(id) on delete cascade,
    requested_by uuid not null references iam.user_account(id),
    decided_by uuid references iam.user_account(id),
    status text not null default 'PENDING',
    arguments_hash text not null,
    expires_at timestamptz not null,
    decision_note text,
    created_at timestamptz not null default now(),
    decided_at timestamptz,
    constraint ck_approval_status check (status in ('PENDING','APPROVED','REJECTED','EXPIRED'))
);
create index ix_approval_pending on automation.approval_request(status,expires_at,id) where status='PENDING';

insert into automation.agent_definition(id,code,name,description,risk_level,allowed_tools) values
 (gen_random_uuid(),'RESEARCH','研究 Agent','检索有权内容并生成带引用的研究结果。','L0','{SEARCH_KNOWLEDGE,READ_CONTENT}'),
 (gen_random_uuid(),'SUBSCRIPTION','订阅 Agent','创建或修改当前用户自己的订阅。','L1','{SEARCH_KNOWLEDGE,MANAGE_OWN_SUBSCRIPTION}'),
 (gen_random_uuid(),'SOURCE_OPS','信源运营 Agent','检查公开信源健康并提出配置建议。','L2','{READ_SOURCE_HEALTH,PROBE_SOURCE}'),
 (gen_random_uuid(),'CRAWL_OPS','采集运维 Agent','诊断采集失败与队列积压。','L2','{READ_CRAWL_STATUS,RETRY_CRAWL}');

insert into iam.permission(id,code,description) values
 (gen_random_uuid(),'agent:use','运行低风险 Agent'),
 (gen_random_uuid(),'agent:approve','审批 Agent 高风险工具调用')
on conflict(code) do nothing;
insert into iam.role_permission(role_id,permission_id)
select r.id,p.id from iam.role r join iam.permission p on
 (p.code='agent:use' and r.code in ('USER','EDITOR','OPERATOR','ADMIN')) or
 (p.code='agent:approve' and r.code in ('OPERATOR','ADMIN'))
on conflict do nothing;
