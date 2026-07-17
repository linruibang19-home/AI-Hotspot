create table iam.role (
    id uuid primary key default gen_random_uuid(),
    code text not null,
    display_name text not null,
    builtin boolean not null default true,
    created_at timestamptz not null default now(),
    constraint uq_role_code unique (code),
    constraint ck_role_code check (code in ('USER', 'EDITOR', 'OPERATOR', 'ADMIN'))
);

create table iam.permission (
    id uuid primary key default gen_random_uuid(),
    code text not null,
    description text not null,
    created_at timestamptz not null default now(),
    constraint uq_permission_code unique (code)
);

create table iam.user_role (
    user_id uuid not null,
    role_id uuid not null,
    assigned_by uuid,
    assigned_at timestamptz not null default now(),
    primary key (user_id, role_id),
    constraint fk_user_role__user foreign key (user_id) references iam.user_account (id),
    constraint fk_user_role__role foreign key (role_id) references iam.role (id),
    constraint fk_user_role__assigned_by foreign key (assigned_by) references iam.user_account (id)
);

create index ix_user_role_role on iam.user_role (role_id, user_id);
create index ix_user_role_assigned_by on iam.user_role (assigned_by) where assigned_by is not null;

create table iam.role_permission (
    role_id uuid not null,
    permission_id uuid not null,
    primary key (role_id, permission_id),
    constraint fk_role_permission__role foreign key (role_id) references iam.role (id),
    constraint fk_role_permission__permission foreign key (permission_id) references iam.permission (id)
);

create index ix_role_permission_permission on iam.role_permission (permission_id, role_id);

alter table iam.invitation_code
    add constraint ck_invitation_code_default_role
    check (default_role in ('USER', 'EDITOR', 'OPERATOR'));

create table source.source_entity (
    id uuid primary key,
    name text not null,
    slug text not null,
    entity_type text not null,
    country_code text,
    official_level text not null,
    authority_score numeric(5,2) not null default 50,
    website_url text,
    logo_url text,
    status text not null default 'DRAFT',
    version bigint not null default 0,
    created_by uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_source_entity_slug unique (slug),
    constraint ck_source_entity_type check (entity_type in ('COMPANY', 'RESEARCH', 'MEDIA', 'COMMUNITY', 'PROJECT', 'OTHER')),
    constraint ck_source_entity_official_level check (official_level in ('OFFICIAL', 'FIRST_PARTY', 'THIRD_PARTY')),
    constraint ck_source_entity_authority check (authority_score between 0 and 100),
    constraint ck_source_entity_status check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    constraint fk_source_entity__created_by foreign key (created_by) references iam.user_account (id)
);

create index ix_source_entity_status_created on source.source_entity (status, created_at desc, id desc);
create index ix_source_entity_created_by on source.source_entity (created_by);

create table source.source_endpoint (
    id uuid primary key,
    source_entity_id uuid not null,
    name text not null,
    url text not null,
    normalized_url text not null,
    endpoint_type text not null,
    connector_type text not null,
    language text,
    polling_interval_seconds integer not null default 3600,
    display_policy text not null default 'SUMMARY_ONLY',
    index_policy text not null default 'METADATA_ONLY',
    authority_override numeric(5,2),
    config jsonb not null default '{}'::jsonb,
    credential_ref text,
    status text not null default 'DRAFT',
    health_status text not null default 'UNKNOWN',
    last_success_at timestamptz,
    last_failure_at timestamptz,
    failure_count integer not null default 0,
    last_probe_status integer,
    last_probe_latency_ms bigint,
    version bigint not null default 0,
    created_by uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_source_endpoint__entity foreign key (source_entity_id) references source.source_entity (id),
    constraint fk_source_endpoint__created_by foreign key (created_by) references iam.user_account (id),
    constraint ck_source_endpoint_type check (endpoint_type in ('RSS', 'ATOM', 'WEBSITE', 'SITEMAP', 'GITHUB', 'HUGGING_FACE', 'ARXIV', 'OPENREVIEW', 'HACKER_NEWS', 'PUBLIC_MEDIA', 'PUBLIC_COMMUNITY')),
    constraint ck_source_endpoint_interval check (polling_interval_seconds between 300 and 2592000),
    constraint ck_source_endpoint_display_policy check (display_policy in ('FULLTEXT_ALLOWED', 'SUMMARY_ONLY', 'LINK_ONLY', 'HIDDEN')),
    constraint ck_source_endpoint_index_policy check (index_policy in ('PUBLIC_RAG', 'PRIVATE_RAG', 'METADATA_ONLY', 'NO_INDEX')),
    constraint ck_source_endpoint_authority check (authority_override is null or authority_override between 0 and 100),
    constraint ck_source_endpoint_status check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    constraint ck_source_endpoint_health check (health_status in ('UNKNOWN', 'HEALTHY', 'WARNING', 'FAILED')),
    constraint ck_source_endpoint_failure_count check (failure_count >= 0)
);

create unique index uq_source_endpoint_active_url on source.source_endpoint (normalized_url)
where status <> 'ARCHIVED';
create index ix_source_endpoint_entity on source.source_endpoint (source_entity_id, created_at, id);
create index ix_source_endpoint_status_health on source.source_endpoint (status, health_status, updated_at desc);
create index ix_source_endpoint_created_by on source.source_endpoint (created_by);

create table governance.audit_log (
    id uuid primary key,
    actor_id uuid,
    actor_type text not null,
    action text not null,
    target_type text not null,
    target_id uuid,
    before_data jsonb,
    after_data jsonb,
    client_ip text,
    user_agent text,
    correlation_id text,
    trace_id text,
    created_at timestamptz not null default now(),
    constraint fk_audit_log__actor foreign key (actor_id) references iam.user_account (id),
    constraint ck_audit_log_actor_type check (actor_type in ('USER', 'SYSTEM'))
);

create index ix_audit_log_target on governance.audit_log (target_type, target_id, created_at desc, id desc);
create index ix_audit_log_actor on governance.audit_log (actor_id, created_at desc) where actor_id is not null;

insert into iam.role (code, display_name) values
    ('USER', '普通用户'),
    ('EDITOR', '内容编辑'),
    ('OPERATOR', '运营人员'),
    ('ADMIN', '管理员')
on conflict (code) do nothing;

insert into iam.permission (code, description) values
    ('profile:read', '读取本人账号'),
    ('source:read', '读取信源管理数据'),
    ('source:write', '新增和修改信源'),
    ('source:probe', '执行信源探测'),
    ('source:activate', '启用或暂停信源入口'),
    ('user:manage', '管理用户账号'),
    ('invitation:manage', '管理邀请码'),
    ('role:assign', '分配角色'),
    ('audit:read', '读取审计记录')
on conflict (code) do nothing;

insert into iam.role_permission (role_id, permission_id)
select r.id, p.id
from iam.role r
join iam.permission p on
    (r.code in ('USER', 'EDITOR', 'OPERATOR', 'ADMIN') and p.code = 'profile:read')
    or (r.code in ('OPERATOR', 'ADMIN') and p.code in ('source:read', 'source:write', 'source:probe', 'source:activate', 'audit:read'))
    or (r.code = 'ADMIN' and p.code in ('user:manage', 'invitation:manage', 'role:assign'))
on conflict do nothing;
