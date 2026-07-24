alter table automation.agent_run
    add column retry_of_run_id uuid references automation.agent_run(id),
    add column attempt_no integer not null default 1,
    add column permission_snapshot jsonb not null default '{}'::jsonb,
    add column failure_code text,
    add column cancel_requested_at timestamptz,
    add column last_heartbeat_at timestamptz,
    add column estimated_cost numeric(18,8) not null default 0;

alter table automation.agent_run
    add constraint ck_agent_run_attempt_no check (attempt_no between 1 and 3),
    add constraint ck_agent_run_estimated_cost check (estimated_cost >= 0);

alter table automation.tool_call
    add column started_at timestamptz,
    add column completed_at timestamptz;

alter table automation.tool_call drop constraint ck_tool_call_status;
alter table automation.tool_call
    add constraint ck_tool_call_status
    check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED','DENIED'));

update automation.agent_run
set last_heartbeat_at=coalesce(started_at,created_at)
where status='RUNNING' and last_heartbeat_at is null;

create index ix_agent_run_retry on automation.agent_run(retry_of_run_id,attempt_no)
where retry_of_run_id is not null;
create index ix_agent_run_recovery on automation.agent_run(status,last_heartbeat_at)
where status='RUNNING';

comment on column automation.agent_run.retry_of_run_id is '本次安全重试所基于的上一运行';
comment on column automation.agent_run.attempt_no is '同一目标的执行尝试序号，最多三次';
comment on column automation.agent_run.permission_snapshot is '创建运行时角色和权限快照，执行前仍复核当前权限';
comment on column automation.agent_run.failure_code is '结构化失败或取消原因';
comment on column automation.agent_run.last_heartbeat_at is '运行步骤心跳，用于识别进程中断';
comment on column automation.agent_run.estimated_cost is '本次 Agent 已记录模型调用估算成本';
comment on column automation.tool_call.started_at is '工具真正越过执行边界的时间';
comment on column automation.tool_call.completed_at is '工具完成或终止时间';
