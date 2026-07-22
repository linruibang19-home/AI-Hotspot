-- SRC-02: retain the explicit origin of source publication timestamps and recover
-- only high-confidence dates exposed in listing text or canonical URL paths.
alter table source.raw_entry
    add column source_published_at_source text,
    add constraint ck_raw_entry_source_published_at_source check (
        source_published_at_source is null
        or source_published_at_source ~ '^[A-Z][A-Z0-9_]{1,79}$'
    );

alter table content.content_item
    add column source_published_at_source text,
    add constraint ck_content_item_source_published_at_source check (
        source_published_at_source is null
        or source_published_at_source ~ '^[A-Z][A-Z0-9_]{1,79}$'
    );

alter table knowledge.chunk
    add column source_published_at_source text,
    add constraint ck_chunk_source_published_at_source check (
        source_published_at_source is null
        or source_published_at_source ~ '^[A-Z][A-Z0-9_]{1,79}$'
    );

with text_dates as (
    select r.id,
           to_date((m.parts)[1] || ' ' || (m.parts)[2] || ' ' || (m.parts)[3], 'Mon DD YYYY')::timestamptz recovered_at,
           'LINK_TEXT'::text provenance,
           1 priority
    from source.raw_entry r
    cross join lateral regexp_match(
        r.raw_title,
        '\m(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) ([0-9]{1,2}), ([0-9]{4})\M',
        'i'
    ) m(parts)
    where r.source_published_at is null
), slash_url_dates as (
    select r.id,
           make_date((m.parts)[1]::integer, (m.parts)[2]::integer, (m.parts)[3]::integer)::timestamptz recovered_at,
           'URL_PATH'::text provenance,
           2 priority
    from source.raw_entry r
    cross join lateral regexp_match(
        coalesce(r.canonical_url, r.original_url, ''),
        '/(20[0-9]{2})/(0[1-9]|1[0-2])/([0-2][0-9]|3[01])(?:/|$)'
    ) m(parts)
    where r.source_published_at is null
), dash_url_dates as (
    select r.id,
           make_date((m.parts)[1]::integer, (m.parts)[2]::integer, (m.parts)[3]::integer)::timestamptz recovered_at,
           'URL_PATH'::text provenance,
           3 priority
    from source.raw_entry r
    cross join lateral regexp_match(
        coalesce(r.canonical_url, r.original_url, ''),
        '/(20[0-9]{2})-(0[1-9]|1[0-2])-([0-2][0-9]|3[01])(?:-|/|$)'
    ) m(parts)
    where r.source_published_at is null
), candidates as (
    select * from text_dates
    union all select * from slash_url_dates
    union all select * from dash_url_dates
), selected as (
    select distinct on (id) id, recovered_at, provenance
    from candidates
    where recovered_at >= timestamptz '1990-01-01 00:00:00+00'
      and recovered_at <= now()
    order by id, priority
)
update source.raw_entry r
set source_published_at = s.recovered_at,
    source_published_at_source = s.provenance,
    payload = jsonb_set(
        jsonb_set(r.payload, '{publishedAtSource}', to_jsonb(s.provenance), true),
        '{normalized,publishedAtSource}', to_jsonb(s.provenance), true
    ),
    updated_at = now()
from selected s
where r.id = s.id;

update source.raw_entry r
set source_published_at_source = coalesce(
        nullif(r.payload->>'publishedAtSource', ''),
        nullif(r.payload->'normalized'->>'publishedAtSource', ''),
        case e.endpoint_type
            when 'RSS' then 'LEGACY_FEED_DATETIME'
            when 'ATOM' then 'LEGACY_FEED_DATETIME'
            when 'ARXIV' then 'LEGACY_FEED_DATETIME'
            when 'SITEMAP' then 'LEGACY_SITEMAP_LASTMOD'
            when 'GITHUB' then 'LEGACY_GITHUB_DATETIME'
            when 'HUGGING_FACE' then 'LEGACY_HUGGING_FACE_DATETIME'
            when 'OPENREVIEW' then 'LEGACY_OPENREVIEW_DATETIME'
            when 'HACKER_NEWS' then 'LEGACY_HACKER_NEWS_DATETIME'
            when 'WEBSITE' then 'LEGACY_EXPLICIT_DATE'
            else 'LEGACY_CONNECTOR_DATETIME'
        end
    ),
    updated_at = now()
from source.source_endpoint e
where e.id = r.endpoint_id
  and r.source_published_at is not null
  and r.source_published_at_source is null;

update content.content_item c
set source_published_at = r.source_published_at,
    source_published_at_source = r.source_published_at_source,
    updated_at = now()
from source.raw_entry r
where r.id = c.raw_entry_id
  and r.source_published_at is not null
  and (c.source_published_at is null
       or c.source_published_at_source is distinct from r.source_published_at_source);

update knowledge.chunk ch
set source_published_at = c.source_published_at,
    source_published_at_source = c.source_published_at_source,
    effective_published_at = c.effective_published_at
from knowledge.document d
join content.content_item c on c.id = d.content_item_id
where ch.document_id = d.id
  and c.source_published_at is not null
  and (ch.source_published_at is null
       or ch.source_published_at_source is distinct from c.source_published_at_source);

create or replace view source.publication_time_quality_current as
select e.id endpoint_id,
       e.name endpoint_name,
       e.endpoint_type,
       count(r.id)::integer raw_count,
       count(r.id) filter (where r.source_published_at is not null)::integer dated_count,
       count(r.id) filter (where r.source_published_at is null)::integer missing_count,
       count(r.id) filter (
           where r.source_published_at is not null
             and r.source_published_at_source is not null
       )::integer provenance_count,
       count(r.id) filter (where r.source_published_at > now())::integer future_count,
       case when count(r.id) = 0 then 0
            else round(count(r.id) filter (where r.source_published_at is not null)::numeric / count(r.id), 5)
       end completeness_rate,
       case when count(r.id) filter (where r.source_published_at is not null) = 0 then 0
            else round(count(r.id) filter (
                where r.source_published_at is not null
                  and r.source_published_at_source is not null
            )::numeric / count(r.id) filter (where r.source_published_at is not null), 5)
       end provenance_rate
from source.source_endpoint e
left join source.raw_entry r on r.endpoint_id = e.id
where e.catalog_kind <> 'TEST' and e.status <> 'ARCHIVED'
group by e.id, e.name, e.endpoint_type;

comment on column source.raw_entry.source_published_at_source is
    '来源发布时间的解析依据，例如 FEED_PUBLISHED、LINK_TEXT、URL_PATH；不得用抓取时间冒充。';
comment on view source.publication_time_quality_current is
    '按 Endpoint 监控来源发布时间完整率、解析来源覆盖率和未来时间异常。';
