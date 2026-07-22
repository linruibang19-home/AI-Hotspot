-- SRC-03: distinguish healthy no-change polls from content/upstream failures.
alter table source.fetch_job
    add column poll_outcome text not null default 'PENDING',
    add constraint ck_fetch_job_poll_outcome check (poll_outcome in (
        'PENDING','NEW_CONTENT','NO_NEW_CONTENT','NOT_MODIFIED',
        'UPSTREAM_FAILURE','CONTENT_FAILURE','CANCELLED'
    ));

alter table source.source_endpoint
    add column last_poll_outcome text not null default 'NEVER',
    add column consecutive_no_change_count integer not null default 0,
    add constraint ck_source_endpoint_last_poll_outcome check (last_poll_outcome in (
        'NEVER','NEW_CONTENT','NO_NEW_CONTENT','NOT_MODIFIED',
        'UPSTREAM_FAILURE','CONTENT_FAILURE','CANCELLED'
    )),
    add constraint ck_source_endpoint_no_change_count check (consecutive_no_change_count >= 0);

update source.fetch_job
set poll_outcome = case
    when status = 'SUCCEEDED' and http_status = 304 then 'NOT_MODIFIED'
    when status = 'SUCCEEDED' and new_entry_count > 0 then 'NEW_CONTENT'
    when status = 'SUCCEEDED' then 'NO_NEW_CONTENT'
    when status = 'CANCELLED' then 'CANCELLED'
    when error_code in ('DNS_FAILURE','NETWORK_FAILURE','UPSTREAM_REJECTED') then 'UPSTREAM_FAILURE'
    when status in ('WAITING_RETRY','FAILED','DEAD_LETTERED') then 'CONTENT_FAILURE'
    else 'PENDING'
end;

with latest as (
    select distinct on (j.endpoint_id)
           j.endpoint_id,
           j.poll_outcome
    from source.fetch_job j
    where j.poll_outcome <> 'PENDING'
    order by j.endpoint_id, j.created_at desc, j.id desc
)
update source.source_endpoint e
set last_poll_outcome = latest.poll_outcome,
    consecutive_no_change_count = case
        when latest.poll_outcome in ('NO_NEW_CONTENT','NOT_MODIFIED') then 1 else 0 end
from latest
where latest.endpoint_id = e.id;

create index ix_fetch_job_poll_outcome_created
    on source.fetch_job (poll_outcome, created_at desc, id desc);

create or replace view source.connector_health_current as
select e.id endpoint_id,
       e.name endpoint_name,
       e.endpoint_type,
       e.status endpoint_status,
       e.health_status,
       e.last_poll_outcome,
       e.consecutive_no_change_count,
       e.last_success_at,
       e.last_failure_at,
       count(j.id) filter (where j.created_at >= now() - interval '7 days')::integer poll_count_7d,
       count(j.id) filter (
           where j.created_at >= now() - interval '7 days'
             and j.poll_outcome = 'NEW_CONTENT'
       )::integer new_content_poll_count_7d,
       count(j.id) filter (
           where j.created_at >= now() - interval '7 days'
             and j.poll_outcome in ('NO_NEW_CONTENT','NOT_MODIFIED')
       )::integer healthy_no_change_poll_count_7d,
       count(j.id) filter (
           where j.created_at >= now() - interval '7 days'
             and j.poll_outcome = 'UPSTREAM_FAILURE'
       )::integer upstream_failure_count_7d,
       count(j.id) filter (
           where j.created_at >= now() - interval '7 days'
             and j.poll_outcome = 'CONTENT_FAILURE'
       )::integer content_failure_count_7d
from source.source_endpoint e
left join source.fetch_job j on j.endpoint_id = e.id
where e.catalog_kind <> 'TEST' and e.status <> 'ARCHIVED'
group by e.id, e.name, e.endpoint_type, e.status, e.health_status,
         e.last_poll_outcome, e.consecutive_no_change_count,
         e.last_success_at, e.last_failure_at;

comment on column source.fetch_job.poll_outcome is
    '单次采集的业务结果；正常零新增和 HTTP 304 不属于失败。';
comment on view source.connector_health_current is
    '按 Endpoint 区分新增、健康无变化、上游失败和内容结构失败的 7 天健康视图。';
