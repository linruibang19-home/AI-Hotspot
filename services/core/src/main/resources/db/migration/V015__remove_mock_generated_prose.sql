-- Applied separately because V011 may already exist in long-lived developer databases.
-- Keep source artifacts and model_run audit evidence, but remove generated Mock prose.
update content.content_item
set summary_zh = null,
    recommendation_reason = null,
    updated_at = now(),
    policy_snapshot = policy_snapshot || jsonb_build_object('mockProseRemovedAt', now())
where lower(coalesce(provider_name, '')) in ('mock','test','fixture')
   or summary_zh ilike '%Mock Provider%'
   or recommendation_reason ilike '%Mock 质量分析%';
