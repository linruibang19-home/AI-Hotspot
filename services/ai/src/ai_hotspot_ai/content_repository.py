import hashlib
import re
import uuid
from datetime import UTC, datetime

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ai_hotspot_ai.repository_common import (
    AlreadyCompleted,
    ContentContext,
    database_dsn,
    insert_dead_letter,
)


def load_content(payload: dict[str, object]) -> ContentContext:
    content_id = uuid.UUID(str(payload["payload"]["contentItemId"]))
    with (
        psycopg.connect(database_dsn()) as connection,
        connection.cursor(row_factory=dict_row) as cursor,
    ):
        cursor.execute(
            """
            select c.id as content_id, c.raw_entry_id, r.raw_title as title,
                   r.raw_summary as summary, s.name as source_name,
                   c.source_official_level, c.display_policy, c.index_policy, e.config,
                   c.canonical_url, c.source_published_at,
                   (coalesce(e.authority_override, s.authority_score) * e.quality_weight)::float
                       as authority_score
            from content.content_item c
            join source.raw_entry r on r.id = c.raw_entry_id
            join source.source_entity s on s.id = c.source_entity_id
            join source.source_endpoint e on e.id = c.endpoint_id
            where c.id = %s and c.admission_status = 'PENDING'
            """,
            (content_id,),
        )
        row = cursor.fetchone()
        if not row:
            cursor.execute(
                "select admission_status from content.content_item where id = %s", (content_id,)
            )
            existing = cursor.fetchone()
            if existing and existing["admission_status"] == "PASSED":
                raise AlreadyCompleted
            raise RuntimeError("Content item is missing or not pending")
        return ContentContext(**row)


def finish_content(
    context: ContentContext,
    *,
    title_zh: str,
    summary: str,
    reason: str,
    category_code: str,
    tags: list[str],
    entities: list[dict[str, object]],
    fact_status: str,
    confidence_score: float,
    quality_dimensions: dict[str, float],
    generation_metadata: dict[str, object],
    relevance_score: float,
    quality_score: float,
    final_score: float,
    provider_name: str,
    provider_model: str,
    relevance_threshold: float,
    quality_threshold: float,
) -> bool:
    auto_publish = bool(context.config.get("autoPublish", False))
    passed = relevance_score >= relevance_threshold and quality_score >= quality_threshold
    duplicate_id, duplicate_similarity = _find_duplicate(context)
    is_duplicate = duplicate_id is not None
    unconfirmed_trusted = fact_status != "UNCONFIRMED" or _may_publish_unconfirmed(
        context.source_official_level, context.authority_score
    )
    review_required = passed and fact_status == "UNCONFIRMED" and not unconfirmed_trusted
    published = (
        passed
        and auto_publish
        and context.display_policy != "HIDDEN"
        and fact_status != "DEBUNKED"
        and unconfirmed_trusted
        and not is_duplicate
    )
    featured = published and final_score >= 80 and fact_status == "CONFIRMED"
    with psycopg.connect(database_dsn()) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            update content.content_item
            set title_zh = %s, summary_zh = %s, recommendation_reason = %s,
                category_code = %s, language_code = 'zh-CN', fact_status = %s,
                confidence_score = %s, quality_dimensions = %s, generation_metadata = %s,
                relevance_score = %s, quality_score = %s, final_score = %s,
                featured = %s, is_duplicate = %s, duplicate_of_id = %s,
                duplicate_similarity = %s, admission_status = %s, publication_status = %s,
                visibility = %s, provider_name = %s, provider_model = %s,
                processing_version = 'm5-v1', processed_at = now(), version = version + 1,
                published_at = case when %s then now() else null end,
                updated_at = now()
            where id = %s and admission_status = 'PENDING'
            """,
            (
                title_zh[:300],
                summary[:4000],
                reason[:1000],
                category_code[:80],
                fact_status,
                confidence_score,
                Jsonb(quality_dimensions),
                Jsonb(generation_metadata),
                relevance_score,
                quality_score,
                final_score,
                featured,
                is_duplicate,
                duplicate_id,
                duplicate_similarity,
                "REVIEW_REQUIRED" if review_required else ("PASSED" if passed else "FAILED"),
                "PUBLISHED" if published else ("CANDIDATE" if passed else "REJECTED"),
                "PUBLIC" if published else "PRIVATE",
                provider_name,
                provider_model,
                published,
                context.content_id,
            ),
        )
        for tag in tags[:5]:
            cursor.execute(
                """insert into content.content_tag
                   (content_item_id, tag, source, confidence)
                   values (%s, %s, 'AI', 85) on conflict do nothing""",
                (context.content_id, tag[:40]),
            )
        for entity in entities[:10]:
            name = str(entity.get("name") or "")[:120]
            if not name:
                continue
            cursor.execute(
                """
                insert into content.content_entity
                    (id, content_item_id, entity_type, canonical_name,
                     display_name, confidence)
                values (%s, %s, %s, lower(%s), %s, %s) on conflict do nothing
                """,
                (
                    uuid.uuid4(),
                    context.content_id,
                    str(entity.get("type") or "OTHER"),
                    name,
                    name,
                    float(entity.get("confidence") or 80),
                ),
            )
        cursor.execute(
            "delete from content.content_topic "
            "where content_item_id = %s and assignment_source = 'RULE'",
            (context.content_id,),
        )
        cursor.execute(
            """
            insert into content.content_topic(content_item_id,topic_id,assignment_source,confidence)
            select c.id,t.id,'RULE',case when t.group_code='COMPANY_MODEL' then 92 else 82 end
            from content.content_item c
            join source.source_entity s on s.id=c.source_entity_id
            cross join content.topic t
            where c.id=%s and t.status='ACTIVE' and (
              (t.group_code='COMPANY_MODEL' and exists(
                select 1 from unnest(string_to_array(t.query_text,' ')) token
                where length(token)>=2 and (
                  strpos(lower(coalesce(c.title_zh,c.original_title)),lower(token))>0
                  or strpos(lower(s.name),lower(token))>0)))
              or (t.group_code='TECHNOLOGY' and exists(
                select 1 from content.content_tag tag where tag.content_item_id=c.id and (
                  lower(tag.tag)=lower(t.name) or strpos(lower(t.name),lower(tag.tag))>0
                  or strpos(lower(tag.tag),lower(split_part(t.name,' ',1)))>0)))
              or (t.group_code='CONTENT_FORM' and (
                (t.slug='model-release' and c.category_code='MODEL_RELEASE') or
                (t.slug='research-paper' and c.category_code='RESEARCH') or
                (t.slug='benchmark' and exists(
                  select 1 from content.content_tag tag
                  where tag.content_item_id=c.id and tag.tag='评测基准')) or
                (t.slug='product-update' and c.content_type='RELEASE') or
                (t.slug='industry' and c.category_code='INDUSTRY')))
            ) on conflict(content_item_id,topic_id) do nothing
            """,
            (context.content_id,),
        )
        cursor.execute(
            """
            insert into content.model_run (id, content_item_id, provider_name, provider_model,
                prompt_version, status, input_fingerprint, output_data)
            values (%s, %s, %s, %s, 'content-analysis-v1', 'SUCCEEDED', %s, %s)
            """,
            (
                uuid.uuid4(),
                context.content_id,
                provider_name,
                provider_model,
                _fingerprint(f"{context.title}\n{context.summary or ''}"),
                Jsonb(generation_metadata),
            ),
        )
        _upsert_event_cluster(
            cursor, context, title_zh, summary, category_code, fact_status, entities
        )
        cursor.execute(
            """
            update source.raw_entry set normalization_status = 'NORMALIZED', updated_at = now()
            where id = %s
            """,
            (context.raw_entry_id,),
        )
    return published


def finish_mock_content(context: ContentContext, provider_name: str, provider_model: str) -> None:
    """Keep the authentic source record while refusing to persist synthetic analysis prose."""
    with psycopg.connect(database_dsn()) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            update content.content_item
            set title_zh = null, summary_zh = null, recommendation_reason = null,
                category_code = null, confidence_score = null, quality_dimensions = '{}'::jsonb,
                generation_metadata =
                    jsonb_build_object('developmentOnly', true, 'publishable', false),
                relevance_score = null, quality_score = null, final_score = null,
                featured = false, admission_status = 'FAILED', publication_status = 'REJECTED',
                visibility = 'PRIVATE', provider_name = %s, provider_model = %s,
                processing_version = 'm7-real-provider-gate', processed_at = now(),
                published_at = null, version = version + 1, updated_at = now()
            where id = %s and admission_status = 'PENDING'
            """,
            (provider_name, provider_model, context.content_id),
        )
        cursor.execute(
            """
            insert into content.model_run (id, content_item_id, provider_name, provider_model,
                prompt_version, status, input_fingerprint, output_data)
            values (%s, %s, %s, %s, 'development-provider-gate-v1', 'SUCCEEDED', %s,
                jsonb_build_object('developmentOnly', true, 'publishable', false))
            """,
            (
                uuid.uuid4(),
                context.content_id,
                provider_name,
                provider_model,
                _fingerprint(f"{context.title}\n{context.summary or ''}"),
            ),
        )
        cursor.execute(
            "update source.raw_entry set normalization_status='NORMALIZED',updated_at=now() "
            "where id=%s",
            (context.raw_entry_id,),
        )


def _find_duplicate(context: ContentContext) -> tuple[uuid.UUID | None, float | None]:
    with (
        psycopg.connect(database_dsn()) as connection,
        connection.cursor(row_factory=dict_row) as cursor,
    ):
        if context.canonical_url:
            cursor.execute(
                """select id from content.content_item
                   where id <> %s and canonical_url = %s
                     and admission_status in ('PASSED', 'REVIEW_REQUIRED')
                   order by created_at limit 1""",
                (context.content_id, context.canonical_url),
            )
            exact = cursor.fetchone()
            if exact:
                return exact["id"], 1.0
        cursor.execute(
            """select id, original_title from content.content_item where id <> %s
               and created_at >= now() - interval '30 days' and admission_status = 'PASSED'
               order by created_at desc limit 300""",
            (context.content_id,),
        )
        current = _title_tokens(context.title)
        best: tuple[uuid.UUID | None, float] = (None, 0.0)
        for row in cursor.fetchall():
            other = _title_tokens(row["original_title"])
            union = current | other
            score = len(current & other) / len(union) if union else 0.0
            if score > best[1]:
                best = row["id"], score
        return (best[0], round(best[1], 5)) if best[1] >= 0.88 else (None, None)


def _upsert_event_cluster(cursor, context, title, summary, category, fact_status, entities):
    entity_names = sorted(
        str(item.get("name") or "").lower() for item in entities if item.get("name")
    )[:3]
    title_features = sorted(_title_tokens(title))[:6]
    key_material = "|".join([category, *entity_names, *title_features])
    cluster_key = _fingerprint(key_material)[:32]
    event_id = uuid.uuid4()
    seen_at = context.source_published_at or datetime.now(UTC)
    cursor.execute(
        """
        insert into content.event_cluster (id, cluster_key, title, summary, category_code,
            fact_status, primary_content_id, first_seen_at, last_seen_at)
        values (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        on conflict (cluster_key) do update set
            last_seen_at = greatest(event_cluster.last_seen_at, excluded.last_seen_at),
            updated_at = now(), version = event_cluster.version + 1
        returning id
        """,
        (
            event_id,
            cluster_key,
            title[:300],
            summary[:1000],
            category,
            fact_status,
            context.content_id,
            seen_at,
            seen_at,
        ),
    )
    cluster_id = cursor.fetchone()[0]
    cursor.execute(
        """insert into content.content_event_relation
           (content_item_id, event_cluster_id, relation_type, confidence, is_primary)
           values (%s, %s, 'PRIMARY', 85, true) on conflict do nothing""",
        (context.content_id, cluster_id),
    )


def _title_tokens(value: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]{2,}", value.lower()))


def _fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _may_publish_unconfirmed(official_level: str, authority_score: float) -> bool:
    return official_level in {"OFFICIAL", "FIRST_PARTY"} or authority_score >= 80


def mark_content_failure(
    payload: dict[str, object], code: str, error: str, attempt_no: int, max_attempts: int
) -> bool:
    should_retry = attempt_no < max_attempts
    if not should_retry:
        content_id = uuid.UUID(str(payload["payload"]["contentItemId"]))
        with psycopg.connect(database_dsn()) as connection, connection.cursor() as cursor:
            cursor.execute(
                """
                update content.content_item
                set admission_status = 'FAILED', publication_status = 'REJECTED',
                    visibility = 'PRIVATE', updated_at = now()
                where id = %s
                """,
                (content_id,),
            )
            insert_dead_letter(cursor, payload, "q.content.worker", code, error, attempt_no)
    return should_retry
