create table knowledge.provider_connection (
    id uuid primary key,
    owner_user_id uuid references iam.user_account(id) on delete cascade,
    scope text not null default 'PLATFORM',
    display_name text not null,
    vendor text not null,
    api_protocol text not null default 'OPENAI_COMPATIBLE',
    base_url text not null,
    credential_kind text not null default 'ENCRYPTED',
    credential_ref text,
    credential_ciphertext text,
    credential_nonce text,
    key_fingerprint text,
    key_last_four text,
    capabilities text[] not null default '{}',
    status text not null default 'DISABLED',
    last_test_status text not null default 'NOT_TESTED',
    last_tested_at timestamptz,
    last_error_code text,
    created_by uuid references iam.user_account(id),
    updated_by uuid references iam.user_account(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_provider_connection_scope check (scope in ('PLATFORM','USER')),
    constraint ck_provider_connection_scope_owner check (
        (scope='PLATFORM' and owner_user_id is null) or
        (scope='USER' and owner_user_id is not null)
    ),
    constraint ck_provider_connection_protocol check (
        api_protocol in ('OPENAI_COMPATIBLE','SILICONFLOW')
    ),
    constraint ck_provider_connection_credential check (
        credential_kind in ('ENVIRONMENT','ENCRYPTED')
    ),
    constraint ck_provider_connection_status check (
        status in ('ACTIVE','DISABLED','DEGRADED')
    ),
    constraint ck_provider_connection_test_status check (
        last_test_status in ('NOT_TESTED','PASS','FAIL')
    ),
    constraint ck_provider_connection_capabilities check (
        capabilities <@ array['CONTENT_ANALYSIS','RAG_GENERATION','EMBEDDING','RERANK','AGENT']::text[]
    )
);

create unique index uq_provider_connection_platform_name
    on knowledge.provider_connection(lower(display_name))
    where owner_user_id is null;
create unique index uq_provider_connection_user_name
    on knowledge.provider_connection(owner_user_id,lower(display_name))
    where owner_user_id is not null;
create index ix_provider_connection_owner_status
    on knowledge.provider_connection(owner_user_id,status,updated_at desc);

create table knowledge.user_provider_assignment (
    user_id uuid not null references iam.user_account(id) on delete cascade,
    task_type text not null,
    connection_id uuid not null references knowledge.provider_connection(id) on delete cascade,
    model_name text not null,
    status text not null default 'ACTIVE',
    timeout_ms integer not null default 40000,
    parameters jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id,task_type),
    constraint ck_user_provider_assignment_task check (
        task_type in ('CONTENT_ANALYSIS','RAG_GENERATION','EMBEDDING','RERANK','AGENT')
    ),
    constraint ck_user_provider_assignment_status check (status in ('ACTIVE','DISABLED')),
    constraint ck_user_provider_assignment_timeout check (timeout_ms between 1000 and 120000)
);

create index ix_user_provider_assignment_connection
    on knowledge.user_provider_assignment(connection_id,status);

alter table knowledge.provider_config
    add column connection_id uuid references knowledge.provider_connection(id);

alter table knowledge.provider_metric
    add column connection_id uuid references knowledge.provider_connection(id) on delete set null,
    add column actor_user_id uuid references iam.user_account(id) on delete set null,
    add column query_run_id uuid references research.query_run(id) on delete set null,
    add column credential_scope text not null default 'PLATFORM';

alter table knowledge.provider_metric
    add constraint ck_provider_metric_credential_scope
        check (credential_scope in ('PLATFORM','USER','ENVIRONMENT','FALLBACK'));

create index ix_provider_metric_actor_recent
    on knowledge.provider_metric(actor_user_id,recorded_at desc);
create index ix_provider_metric_connection_recent
    on knowledge.provider_metric(connection_id,recorded_at desc);
create index ix_provider_metric_query_run
    on knowledge.provider_metric(query_run_id);

insert into knowledge.provider_connection(
    id,scope,display_name,vendor,api_protocol,base_url,credential_kind,credential_ref,
    capabilities,status,last_test_status,created_at,updated_at
)
select gen_random_uuid(),'PLATFORM','DeepSeek 平台连接','deepseek','OPENAI_COMPATIBLE',
       coalesce(max(base_url),'https://api.deepseek.com'),'ENVIRONMENT','GENERATION_API_KEY',
       array['CONTENT_ANALYSIS','RAG_GENERATION','AGENT']::text[],
       case when bool_or(status='ACTIVE') then 'ACTIVE' else 'DISABLED' end,
       'NOT_TESTED',now(),now()
from knowledge.provider_config
where task_type in ('CONTENT_ANALYSIS','RAG_GENERATION','AGENT')
having count(*) > 0;

insert into knowledge.provider_connection(
    id,scope,display_name,vendor,api_protocol,base_url,credential_kind,credential_ref,
    capabilities,status,last_test_status,created_at,updated_at
)
select gen_random_uuid(),'PLATFORM','SiliconFlow 平台连接','siliconflow','SILICONFLOW',
       coalesce(max(base_url),'https://api.siliconflow.cn/v1'),'ENVIRONMENT','EMBEDDING_API_KEY / RERANK_API_KEY',
       array['EMBEDDING','RERANK']::text[],
       case when bool_or(status='ACTIVE') then 'ACTIVE' else 'DISABLED' end,
       'NOT_TESTED',now(),now()
from knowledge.provider_config
where task_type in ('EMBEDDING','RERANK')
having count(*) > 0;

update knowledge.provider_config pc
set connection_id=connection.id
from knowledge.provider_connection connection
where connection.scope='PLATFORM'
  and (
    (pc.task_type in ('CONTENT_ANALYSIS','RAG_GENERATION','AGENT') and connection.vendor='deepseek')
    or
    (pc.task_type in ('EMBEDDING','RERANK') and connection.vendor='siliconflow')
  );

comment on table knowledge.provider_connection is '平台或用户拥有的模型厂商连接；密钥只保存 AES-GCM 密文或环境变量引用';
comment on table knowledge.user_provider_assignment is '用户私有 BYOK 连接与具体 AI 任务的路由关系；用户入口启用前保持无数据';
comment on column knowledge.provider_connection.owner_user_id is '为空表示平台连接；非空表示该用户私有 BYOK 连接';
comment on column knowledge.provider_connection.credential_ciphertext is 'AES-GCM 密文，禁止由任何 API 返回';
comment on column knowledge.provider_connection.credential_nonce is 'AES-GCM 随机 nonce，禁止由任何 API 返回';
comment on column knowledge.provider_connection.key_fingerprint is '不可逆 SHA-256 指纹，用于识别重复 Key';
comment on column knowledge.provider_connection.key_last_four is '仅用于掩码展示的末四位';
comment on column knowledge.provider_metric.actor_user_id is '触发本次调用的用户；后台任务可为空';
comment on column knowledge.provider_metric.query_run_id is '关联的 RAG 查询运行，非 RAG 调用可为空';
comment on column knowledge.provider_metric.credential_scope is '本次调用消耗平台、用户、环境变量或降级资源';
