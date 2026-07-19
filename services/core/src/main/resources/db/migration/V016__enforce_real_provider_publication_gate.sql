-- A deterministic development provider may exercise the pipeline, but its prose and
-- scores must never remain in product-facing content or event/topic aggregates.
update content.content_item
set title_zh = null,
    summary_zh = null,
    recommendation_reason = null,
    category_code = null,
    confidence_score = null,
    quality_dimensions = '{}'::jsonb,
    relevance_score = null,
    quality_score = null,
    final_score = null,
    featured = false,
    admission_status = 'FAILED',
    publication_status = 'REJECTED',
    visibility = 'PRIVATE',
    published_at = null,
    updated_at = now()
where lower(coalesce(provider_name, '')) in ('mock','test','fixture');

delete from content.content_event_relation member
using content.content_item item
where member.content_item_id = item.id
  and lower(coalesce(item.provider_name, '')) in ('mock','test','fixture');

delete from content.event_cluster cluster
where not exists (
  select 1 from content.content_event_relation member where member.event_cluster_id = cluster.id
)
and not exists (
  select 1 from governance.content_ticket ticket where ticket.event_cluster_id = cluster.id
);

create or replace function content.enforce_real_provider_publication_gate()
returns trigger language plpgsql as $$
begin
  if lower(coalesce(new.provider_name, '')) in ('mock','test','fixture') then
    new.title_zh := null;
    new.summary_zh := null;
    new.recommendation_reason := null;
    new.category_code := null;
    new.confidence_score := null;
    new.quality_dimensions := '{}'::jsonb;
    new.relevance_score := null;
    new.quality_score := null;
    new.final_score := null;
    new.featured := false;
    new.admission_status := 'FAILED';
    new.publication_status := 'REJECTED';
    new.visibility := 'PRIVATE';
    new.published_at := null;
  end if;
  return new;
end $$;

drop trigger if exists trg_content_item_real_provider_gate on content.content_item;
create trigger trg_content_item_real_provider_gate
before insert or update of provider_name, title_zh, summary_zh, recommendation_reason,
  relevance_score, quality_score, final_score, publication_status, visibility
on content.content_item
for each row execute function content.enforce_real_provider_publication_gate();
