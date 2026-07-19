-- M7: real-provider publication gate, permission-first RAG, evaluation and monitoring.

-- Mock output is retained for audit, but can never remain public. Content without a
-- successful non-mock model run is also quarantined until a real provider reprocesses it.
update content.content_item ci
set publication_status = 'UNPUBLISHED', visibility = 'PRIVATE', admission_status = 'REVIEW_REQUIRED',
    featured = false, updated_at = now(),
    policy_snapshot = policy_snapshot || jsonb_build_object(
        'quarantinedAt', now(), 'quarantineReason', 'REAL_LLM_ANALYSIS_REQUIRED')
where (publication_status = 'PUBLISHED' or visibility = 'PUBLIC')
  and not exists (
      select 1 from content.model_run mr
      where mr.content_item_id = ci.id and mr.status = 'SUCCEEDED'
        and lower(mr.provider_name) not in ('mock', 'test', 'fixture')
  );

create table knowledge.dataset (
    id uuid primary key,
    code text not null unique,
    name text not null,
    visibility text not null default 'PUBLIC',
    status text not null default 'ACTIVE',
    chunk_size integer not null default 900,
    chunk_overlap integer not null default 120,
    embedding_model text not null default 'BAAI/bge-m3',
    created_by uuid references iam.user_account(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_dataset_visibility check (visibility in ('PUBLIC','PRIVATE')),
    constraint ck_dataset_status check (status in ('ACTIVE','PAUSED','ARCHIVED')),
    constraint ck_dataset_chunk check (chunk_size between 200 and 4000 and chunk_overlap between 0 and 1000 and chunk_overlap < chunk_size)
);

create table knowledge.dataset_acl (
    dataset_id uuid not null references knowledge.dataset(id) on delete cascade,
    principal_type text not null,
    principal_id uuid not null,
    permission text not null default 'READ',
    created_at timestamptz not null default now(),
    primary key (dataset_id, principal_type, principal_id, permission),
    constraint ck_dataset_acl_principal check (principal_type in ('USER','ROLE')),
    constraint ck_dataset_acl_permission check (permission in ('READ','WRITE','MANAGE'))
);

create table knowledge.document (
    id uuid primary key,
    dataset_id uuid not null references knowledge.dataset(id) on delete cascade,
    content_item_id uuid references content.content_item(id) on delete cascade,
    title text not null,
    index_policy text not null,
    status text not null default 'PENDING',
    content_hash text not null,
    indexed_at timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (dataset_id, content_item_id),
    constraint ck_knowledge_document_policy check (index_policy in ('PUBLIC_RAG','PRIVATE_RAG')),
    constraint ck_knowledge_document_status check (status in ('PENDING','INDEXED','FAILED','REMOVED'))
);

create table knowledge.chunk (
    id uuid primary key,
    document_id uuid not null references knowledge.document(id) on delete cascade,
    ordinal integer not null,
    content_text text not null,
    token_count integer not null default 0,
    source_url text,
    source_published_at timestamptz,
    search_tsv tsvector generated always as (to_tsvector('simple', content_text)) stored,
    embedding vector(1024),
    embedding_model text,
    embedded_at timestamptz,
    created_at timestamptz not null default now(),
    unique (document_id, ordinal),
    constraint ck_chunk_ordinal check (ordinal >= 0),
    constraint ck_chunk_token_count check (token_count >= 0)
);
create index ix_knowledge_chunk_fts on knowledge.chunk using gin(search_tsv);
create index ix_knowledge_document_dataset_status on knowledge.document(dataset_id, status, updated_at desc);
create index ix_dataset_acl_principal on knowledge.dataset_acl(principal_type, principal_id, permission, dataset_id);

create table research.session (
    id uuid primary key,
    user_id uuid not null references iam.user_account(id) on delete cascade,
    title text not null,
    status text not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_research_session_status check (status in ('ACTIVE','ARCHIVED'))
);

create table research.query_run (
    id uuid primary key,
    session_id uuid not null references research.session(id) on delete cascade,
    user_id uuid not null references iam.user_account(id) on delete cascade,
    question text not null,
    normalized_query text not null,
    filters jsonb not null default '{}'::jsonb,
    permission_snapshot jsonb not null default '{}'::jsonb,
    retrieval_config jsonb not null default '{}'::jsonb,
    answer text,
    answer_status text not null default 'RUNNING',
    generation_provider text,
    generation_model text,
    candidate_count integer not null default 0,
    citation_count integer not null default 0,
    latency_ms bigint,
    error_code text,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_query_answer_status check (answer_status in ('RUNNING','SUCCEEDED','NO_EVIDENCE','FAILED'))
);
create index ix_query_run_user_recent on research.query_run(user_id, created_at desc);

create table research.citation (
    id uuid primary key,
    query_run_id uuid not null references research.query_run(id) on delete cascade,
    chunk_id uuid not null references knowledge.chunk(id),
    citation_no integer not null,
    quote_text text not null,
    retrieval_score numeric(9,6) not null,
    rerank_score numeric(9,6),
    support_status text not null default 'SUPPORTED',
    created_at timestamptz not null default now(),
    unique(query_run_id, citation_no),
    constraint ck_citation_support check (support_status in ('SUPPORTED','PARTIAL','REJECTED'))
);

create table knowledge.provider_config (
    id uuid primary key,
    task_type text not null unique,
    provider_name text not null,
    model_name text not null,
    base_url text,
    credential_ref text,
    status text not null default 'DISABLED',
    timeout_ms integer not null default 45000,
    parameters jsonb not null default '{}'::jsonb,
    updated_by uuid references iam.user_account(id),
    updated_at timestamptz not null default now(),
    constraint ck_provider_task check (task_type in ('CONTENT_ANALYSIS','RAG_GENERATION','EMBEDDING','RERANK','AGENT')),
    constraint ck_provider_status check (status in ('ACTIVE','DISABLED','DEGRADED'))
);

create table knowledge.prompt_version (
    id uuid primary key,
    task_type text not null,
    version text not null,
    template text not null,
    status text not null default 'DRAFT',
    output_schema jsonb not null default '{}'::jsonb,
    created_by uuid references iam.user_account(id),
    approved_by uuid references iam.user_account(id),
    created_at timestamptz not null default now(),
    activated_at timestamptz,
    unique(task_type, version),
    constraint ck_prompt_status check (status in ('DRAFT','EVALUATED','ACTIVE','RETIRED'))
);
create unique index uq_prompt_active_task on knowledge.prompt_version(task_type) where status='ACTIVE';

create table knowledge.evaluation_suite (
    id uuid primary key,
    code text not null unique,
    name text not null,
    capability text not null,
    version text not null,
    status text not null default 'ACTIVE',
    thresholds jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create table knowledge.evaluation_case (
    id uuid primary key,
    suite_id uuid not null references knowledge.evaluation_suite(id) on delete cascade,
    case_key text not null,
    input_data jsonb not null,
    expected_data jsonb not null,
    tags text[] not null default '{}',
    unique(suite_id, case_key)
);
create table knowledge.evaluation_run (
    id uuid primary key,
    suite_id uuid not null references knowledge.evaluation_suite(id),
    status text not null default 'RUNNING',
    provider_snapshot jsonb not null default '{}'::jsonb,
    metrics jsonb not null default '{}'::jsonb,
    passed boolean,
    started_by uuid references iam.user_account(id),
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_evaluation_run_status check (status in ('RUNNING','SUCCEEDED','FAILED'))
);
create table knowledge.provider_metric (
    id bigserial primary key,
    capability text not null,
    provider_name text not null,
    model_name text not null,
    status text not null,
    latency_ms bigint not null,
    input_tokens integer not null default 0,
    output_tokens integer not null default 0,
    estimated_cost numeric(12,6) not null default 0,
    error_code text,
    trace_id uuid,
    recorded_at timestamptz not null default now()
);
create index ix_provider_metric_recent on knowledge.provider_metric(capability, recorded_at desc);

insert into knowledge.dataset(id, code, name, visibility, status, created_at)
values (gen_random_uuid(), 'PUBLIC_AI_CONTENT', '公开 AI 资讯知识库', 'PUBLIC', 'ACTIVE', now());

insert into knowledge.provider_config(id, task_type, provider_name, model_name, base_url, credential_ref, status)
values
 (gen_random_uuid(),'CONTENT_ANALYSIS','deepseek','deepseek-chat',null,'env:GENERATION_API_KEY','DISABLED'),
 (gen_random_uuid(),'RAG_GENERATION','deepseek','deepseek-chat',null,'env:GENERATION_API_KEY','DISABLED'),
 (gen_random_uuid(),'EMBEDDING','BAAI','BAAI/bge-m3',null,'env:EMBEDDING_API_KEY','DISABLED'),
 (gen_random_uuid(),'RERANK','BAAI','BAAI/bge-reranker-v2-m3',null,'env:RERANK_API_KEY','DISABLED'),
 (gen_random_uuid(),'AGENT','deepseek','deepseek-chat',null,'env:GENERATION_API_KEY','DISABLED');

insert into knowledge.evaluation_suite(id, code, name, capability, version, thresholds)
values (gen_random_uuid(),'RAG_BASELINE_ZH','RAG 中文权限与引用基线','RAG','1.0',
        '{"recallAt20":0.85,"ndcgAt10":0.80,"citationSupport":0.95,"aclLeaks":0}'::jsonb);

insert into iam.permission(id, code, description) values
 (gen_random_uuid(),'research:use','使用权限前置 RAG'),
 (gen_random_uuid(),'ai-config:manage','管理模型、Prompt、评测与监控')
on conflict(code) do nothing;
insert into iam.role_permission(role_id, permission_id)
select r.id,p.id from iam.role r join iam.permission p on
 (p.code='research:use' and r.code in ('USER','EDITOR','OPERATOR','ADMIN')) or
 (p.code='ai-config:manage' and r.code in ('OPERATOR','ADMIN'))
on conflict do nothing;
