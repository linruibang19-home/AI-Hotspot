-- Quality hardening: keep platform publication time for audit and introduce the
-- source/effective time used by feeds, reports, topic timelines and RAG.
alter table content.content_item
    add column effective_published_at timestamptz generated always as
        (coalesce(source_published_at, published_at, created_at)) stored;

create index ix_content_public_effective_feed
    on content.content_item (effective_published_at desc, id desc)
    where publication_status = 'PUBLISHED' and visibility = 'PUBLIC'
      and admission_status = 'PASSED' and is_duplicate = false
      and fact_status <> 'DEBUNKED' and provider_name is not null
      and lower(provider_name) not in ('mock', 'test', 'fixture');

alter table knowledge.chunk
    add column content_kind text not null default 'SOURCE_BODY',
    add column source_entity_id uuid references source.source_entity(id),
    add column event_cluster_id uuid references content.event_cluster(id),
    add column source_official_level text,
    add column authority_score numeric(5,2),
    add column quality_score numeric(5,2),
    add column final_score numeric(5,2),
    add column effective_published_at timestamptz,
    add constraint ck_chunk_content_kind check (content_kind in ('SOURCE_BODY','TITLE_SUMMARY','EVENT_SUMMARY')),
    add constraint ck_chunk_official_level check (source_official_level is null or source_official_level in ('OFFICIAL','FIRST_PARTY','THIRD_PARTY'));

create index ix_knowledge_chunk_effective_time on knowledge.chunk (effective_published_at desc, id) where embedding is not null;
create index ix_knowledge_chunk_source_time on knowledge.chunk (source_entity_id, effective_published_at desc, id) where embedding is not null;
create index ix_knowledge_chunk_vector_hnsw on knowledge.chunk using hnsw (embedding vector_cosine_ops) where embedding is not null;

alter table research.query_run
    add column query_intent jsonb not null default '{}'::jsonb,
    add column retrieval_diagnostics jsonb not null default '{}'::jsonb,
    add column citation_coverage numeric(6,5),
    add constraint ck_query_citation_coverage check (citation_coverage is null or citation_coverage between 0 and 1);

-- Materialize topics. Company/model topics match title/source only, so an
-- incidental product name in a long dependency list does not pollute a topic.
insert into content.content_topic(content_item_id, topic_id, assignment_source, confidence)
select c.id, t.id, 'RULE', case when t.group_code='COMPANY_MODEL' then 92 else 82 end
from content.content_item c
join source.source_entity s on s.id=c.source_entity_id
cross join content.topic t
where t.status='ACTIVE' and (
    (t.group_code='COMPANY_MODEL' and exists (
        select 1 from unnest(string_to_array(t.query_text,' ')) token
        where length(token)>=2 and (
            strpos(lower(coalesce(c.title_zh,c.original_title)),lower(token))>0
            or strpos(lower(s.name),lower(token))>0)))
    or (t.group_code='TECHNOLOGY' and exists (
        select 1 from content.content_tag tag where tag.content_item_id=c.id and (
            lower(tag.tag)=lower(t.name) or strpos(lower(t.name),lower(tag.tag))>0
            or strpos(lower(tag.tag),lower(split_part(t.name,' ',1)))>0)))
    or (t.group_code='CONTENT_FORM' and (
        (t.slug='model-release' and c.category_code='MODEL_RELEASE') or
        (t.slug='research-paper' and c.category_code='RESEARCH') or
        (t.slug='benchmark' and exists(select 1 from content.content_tag tag where tag.content_item_id=c.id and tag.tag='评测基准')) or
        (t.slug='product-update' and c.content_type='RELEASE') or
        (t.slug='industry' and c.category_code='INDUSTRY')))
)
on conflict(content_item_id,topic_id) do nothing;

update knowledge.document set status='PENDING', indexed_at=null,
    last_error='RAG_V2_REINDEX_REQUIRED', updated_at=now()
where status='INDEXED';
