alter table knowledge.user_provider_assignment
    add column platform_fallback_enabled boolean not null default false,
    add column daily_query_limit integer not null default 50,
    add column monthly_token_limit bigint not null default 5000000;

alter table knowledge.user_provider_assignment
    add constraint ck_user_provider_assignment_daily_limit
        check (daily_query_limit between 1 and 500),
    add constraint ck_user_provider_assignment_monthly_token_limit
        check (monthly_token_limit between 10000 and 100000000);

create index ix_provider_metric_user_scope_recent
    on knowledge.provider_metric(actor_user_id,credential_scope,recorded_at desc)
    where actor_user_id is not null;

comment on column knowledge.user_provider_assignment.platform_fallback_enabled
    is '用户连接不可用时是否明确允许消耗平台凭据；默认关闭，禁止静默回落';
comment on column knowledge.user_provider_assignment.daily_query_limit
    is '用户每日最多发起的 RAG 查询数，按 Asia/Shanghai 自然日统计';
comment on column knowledge.user_provider_assignment.monthly_token_limit
    is '用户私有 Provider 每个自然月允许记录的输入加输出 Token 上限';
