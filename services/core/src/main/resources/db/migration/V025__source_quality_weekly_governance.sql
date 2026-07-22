alter table source.source_endpoint
    add column quality_weight numeric(4,3) not null default 1.000,
    add column quality_state text not null default 'UNASSESSED',
    add column quality_reason text,
    add column quality_evaluated_at timestamptz,
    add constraint ck_source_endpoint_quality_weight check (quality_weight between 0.250 and 1.000),
    add constraint ck_source_endpoint_quality_state check (
        quality_state in ('UNASSESSED','HEALTHY','WATCH','DOWNRANKED','AUTO_PAUSED')
    );

create table source.source_quality_weekly_snapshot (
    id uuid primary key,
    endpoint_id uuid not null references source.source_endpoint(id),
    source_entity_id uuid not null references source.source_entity(id),
    week_start date not null,
    official_level text not null,
    health_status text not null,
    item_count integer not null,
    published_count integer not null,
    duplicate_count integer not null,
    missing_source_time_count integer not null,
    avg_relevance_score numeric(7,3),
    avg_quality_score numeric(7,3),
    avg_final_score numeric(7,3),
    publication_rate numeric(7,5) not null,
    duplicate_rate numeric(7,5) not null,
    source_time_completeness numeric(7,5) not null,
    published_source_share numeric(7,5) not null,
    quality_score numeric(7,3),
    assessment text not null,
    decision_reason text not null,
    action_applied text not null default 'NONE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_source_quality_weekly_endpoint unique (endpoint_id, week_start),
    constraint ck_source_quality_weekly_rates check (
        publication_rate between 0 and 1
        and duplicate_rate between 0 and 1
        and source_time_completeness between 0 and 1
        and published_source_share between 0 and 1
    ),
    constraint ck_source_quality_weekly_assessment check (
        assessment in ('INSUFFICIENT_DATA','HEALTHY','WATCH','UNDERPERFORMING')
    ),
    constraint ck_source_quality_weekly_action check (
        action_applied in ('NONE','DOWNRANKED','PAUSED')
    )
);

create index ix_source_quality_weekly_period
    on source.source_quality_weekly_snapshot (week_start desc, quality_score, endpoint_id);

create index ix_source_quality_weekly_source
    on source.source_quality_weekly_snapshot (source_entity_id, week_start desc);

create or replace view source.source_quality_current as
with content_window as (
    select c.endpoint_id,
           count(*)::integer item_count,
           count(*) filter (where c.publication_status = 'PUBLISHED'
               and c.visibility = 'PUBLIC' and c.admission_status = 'PASSED'
               and not c.is_duplicate and c.fact_status <> 'DEBUNKED')::integer published_count,
           count(*) filter (where c.is_duplicate)::integer duplicate_count,
           count(*) filter (where c.source_published_at is null)::integer missing_source_time_count,
           avg(c.relevance_score) avg_relevance_score,
           avg(c.quality_score) avg_quality_score,
           avg(c.final_score) avg_final_score
    from content.content_item c
    where c.created_at >= now() - interval '7 days'
      and lower(coalesce(c.provider_name, '')) not in ('mock','test','fixture')
    group by c.endpoint_id
), totals as (
    select coalesce(sum(published_count), 0)::numeric total_published from content_window
), scored as (
    select e.id endpoint_id, e.source_entity_id, s.name source_name, e.name endpoint_name,
           e.endpoint_type, e.catalog_kind, e.status endpoint_status, e.health_status,
           s.official_level, s.authority_score, e.quality_weight, e.quality_state,
           coalesce(w.item_count, 0) item_count,
           coalesce(w.published_count, 0) published_count,
           coalesce(w.duplicate_count, 0) duplicate_count,
           coalesce(w.missing_source_time_count, 0) missing_source_time_count,
           round(w.avg_relevance_score, 3) avg_relevance_score,
           round(w.avg_quality_score, 3) avg_quality_score,
           round(w.avg_final_score, 3) avg_final_score,
           case when coalesce(w.item_count, 0) = 0 then 0 else w.published_count::numeric / w.item_count end publication_rate,
           case when coalesce(w.item_count, 0) = 0 then 0 else w.duplicate_count::numeric / w.item_count end duplicate_rate,
           case when coalesce(w.item_count, 0) = 0 then 0 else 1 - w.missing_source_time_count::numeric / w.item_count end source_time_completeness,
           case when t.total_published = 0 then 0 else coalesce(w.published_count, 0) / t.total_published end published_source_share,
           case when coalesce(w.item_count, 0) < 20 then null else round(
               coalesce(w.avg_quality_score, 0) * 0.35
               + (w.published_count::numeric / w.item_count) * 25
               + (1 - w.missing_source_time_count::numeric / w.item_count) * 20
               + (1 - w.duplicate_count::numeric / w.item_count) * 10
               + case e.health_status when 'HEALTHY' then 10 when 'WARNING' then 5 else 0 end,
               3
           ) end quality_score
    from source.source_endpoint e
    join source.source_entity s on s.id = e.source_entity_id
    left join content_window w on w.endpoint_id = e.id
    cross join totals t
    where e.catalog_kind <> 'TEST' and e.status <> 'ARCHIVED'
)
select *, case
    when item_count < 20 then 'INSUFFICIENT_DATA'
    when quality_score >= 70 and source_time_completeness >= 0.80 then 'HEALTHY'
    when quality_score >= 50 and source_time_completeness >= 0.50 then 'WATCH'
    else 'UNDERPERFORMING'
end assessment
from scored;

comment on table source.source_quality_weekly_snapshot is
    '按完整自然周保存 Endpoint 质量、重复率、发布时间完整率和来源份额；自动治理只依据连续周快照。';
