create table governance.system_readiness_check (
    id uuid primary key,
    check_code text not null,
    status text not null,
    detail text not null,
    evidence jsonb not null default '{}'::jsonb,
    checked_at timestamptz not null default now(),
    constraint ck_readiness_status check (status in ('PASS','WARN','FAIL'))
);
create index ix_readiness_recent on governance.system_readiness_check(check_code,checked_at desc);

create table governance.data_retention_policy (
    id uuid primary key,
    object_type text not null unique,
    retention_days integer not null,
    delete_mode text not null default 'DELETE',
    enabled boolean not null default true,
    updated_at timestamptz not null default now(),
    constraint ck_retention_days check (retention_days between 1 and 3650),
    constraint ck_retention_mode check (delete_mode in ('DELETE','ANONYMIZE','ARCHIVE'))
);
insert into governance.data_retention_policy(id,object_type,retention_days,delete_mode) values
 (gen_random_uuid(),'EMAIL_VERIFICATION_CODE',7,'DELETE'),
 (gen_random_uuid(),'PROVIDER_METRIC',180,'DELETE'),
 (gen_random_uuid(),'AGENT_TOOL_ARGUMENTS',90,'ANONYMIZE'),
 (gen_random_uuid(),'FETCH_ARTIFACT',30,'DELETE');

create index ix_content_real_public_gate on content.content_item(published_at desc,id desc)
where publication_status='PUBLISHED' and visibility='PUBLIC' and admission_status='PASSED'
  and provider_name is not null and lower(provider_name) not in ('mock','test','fixture');
