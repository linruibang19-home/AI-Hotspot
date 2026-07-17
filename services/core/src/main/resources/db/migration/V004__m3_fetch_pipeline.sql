alter table source.source_endpoint
    add column next_fetch_at timestamptz,
    add column last_etag text,
    add column last_modified text,
    add column last_content_hash text,
    add column last_fetch_item_count integer not null default 0;

alter table source.source_endpoint
    add constraint ck_source_endpoint_last_fetch_item_count
    check (last_fetch_item_count >= 0);

create table source.fetch_job (
    id uuid primary key,
    endpoint_id uuid not null,
    trigger_type text not null,
    scheduled_window timestamptz not null,
    idempotency_key text not null,
    status text not null default 'QUEUED',
    attempt_count integer not null default 0,
    max_attempts integer not null default 4,
    http_status integer,
    artifact_count integer not null default 0,
    discovered_count integer not null default 0,
    new_entry_count integer not null default 0,
    error_code text,
    last_error text,
    correlation_id uuid not null,
    trace_id uuid not null,
    replay_of_job_id uuid,
    requested_by uuid,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_fetch_job_idempotency_key unique (idempotency_key),
    constraint fk_fetch_job__endpoint foreign key (endpoint_id) references source.source_endpoint (id),
    constraint fk_fetch_job__replay_of foreign key (replay_of_job_id) references source.fetch_job (id),
    constraint fk_fetch_job__requested_by foreign key (requested_by) references iam.user_account (id),
    constraint ck_fetch_job_trigger_type check (trigger_type in ('SCHEDULED', 'MANUAL', 'REPLAY')),
    constraint ck_fetch_job_status check (status in ('QUEUED', 'RUNNING', 'WAITING_RETRY', 'SUCCEEDED', 'FAILED', 'DEAD_LETTERED', 'CANCELLED')),
    constraint ck_fetch_job_attempts check (attempt_count >= 0 and max_attempts between 1 and 10),
    constraint ck_fetch_job_counts check (artifact_count >= 0 and discovered_count >= 0 and new_entry_count >= 0),
    constraint ck_fetch_job_http_status check (http_status is null or http_status between 100 and 599)
);

create index ix_fetch_job_endpoint_created on source.fetch_job (endpoint_id, created_at desc, id desc);
create index ix_fetch_job_status_created on source.fetch_job (status, created_at desc, id desc);
create index ix_fetch_job_replay_of on source.fetch_job (replay_of_job_id) where replay_of_job_id is not null;
create index ix_fetch_job_requested_by on source.fetch_job (requested_by) where requested_by is not null;

alter table source.source_endpoint add column last_fetch_job_id uuid;
alter table source.source_endpoint
    add constraint fk_source_endpoint__last_fetch_job
    foreign key (last_fetch_job_id) references source.fetch_job (id);

create index ix_source_endpoint_due_fetch
    on source.source_endpoint (next_fetch_at, id)
    where status = 'ACTIVE' and endpoint_type in ('RSS', 'ATOM');

create index ix_source_endpoint_last_fetch_job
    on source.source_endpoint (last_fetch_job_id)
    where last_fetch_job_id is not null;

create table source.fetch_artifact (
    id uuid primary key,
    fetch_job_id uuid not null,
    endpoint_id uuid not null,
    attempt_no integer not null,
    request_url text not null,
    final_url text not null,
    http_status integer not null,
    content_type text,
    content_length bigint not null,
    content_hash text not null,
    etag text,
    last_modified text,
    object_bucket text not null,
    object_key text not null,
    fetched_at timestamptz not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint uq_fetch_artifact_job_attempt unique (fetch_job_id, attempt_no),
    constraint uq_fetch_artifact_object unique (object_bucket, object_key),
    constraint fk_fetch_artifact__job foreign key (fetch_job_id) references source.fetch_job (id),
    constraint fk_fetch_artifact__endpoint foreign key (endpoint_id) references source.source_endpoint (id),
    constraint ck_fetch_artifact_attempt check (attempt_no > 0),
    constraint ck_fetch_artifact_http_status check (http_status between 200 and 299),
    constraint ck_fetch_artifact_content_length check (content_length >= 0),
    constraint ck_fetch_artifact_hash check (content_hash ~ '^[0-9a-f]{64}$')
);

create index ix_fetch_artifact_endpoint_fetched on source.fetch_artifact (endpoint_id, fetched_at desc, id desc);

create table source.raw_entry (
    id uuid primary key,
    fetch_artifact_id uuid not null,
    endpoint_id uuid not null,
    external_id text not null,
    original_url text,
    canonical_url text,
    raw_title text not null,
    raw_summary text,
    source_published_at timestamptz,
    author_name text,
    payload jsonb not null,
    entry_hash text not null,
    normalization_status text not null default 'PENDING',
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_raw_entry_endpoint_external unique (endpoint_id, external_id),
    constraint fk_raw_entry__artifact foreign key (fetch_artifact_id) references source.fetch_artifact (id),
    constraint fk_raw_entry__endpoint foreign key (endpoint_id) references source.source_endpoint (id),
    constraint ck_raw_entry_hash check (entry_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_raw_entry_normalization_status check (normalization_status in ('PENDING', 'NORMALIZED', 'FAILED'))
);

create index ix_raw_entry_artifact on source.raw_entry (fetch_artifact_id, id);
create index ix_raw_entry_endpoint_seen on source.raw_entry (endpoint_id, last_seen_at desc, id desc);
create index ix_raw_entry_pending on source.raw_entry (created_at, id) where normalization_status = 'PENDING';

alter table messaging.dead_letter_record
    add column idempotency_key text,
    add column aggregate_type text,
    add column aggregate_id uuid,
    add column correlation_id uuid,
    add column trace_id uuid;

create unique index uq_dead_letter_original_queue
    on messaging.dead_letter_record (original_event_id, queue_name);

insert into iam.permission (code, description) values
    ('fetch:manage', '调度、查看和重放采集任务'),
    ('dead-letter:manage', '查看和重放死信')
on conflict (code) do nothing;

insert into iam.role_permission (role_id, permission_id)
select r.id, p.id
from iam.role r
join iam.permission p on r.code in ('OPERATOR', 'ADMIN')
    and p.code in ('fetch:manage', 'dead-letter:manage')
on conflict do nothing;

