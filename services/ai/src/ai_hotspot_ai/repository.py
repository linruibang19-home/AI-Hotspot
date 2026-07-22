import os
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ai_hotspot_ai.feed import FeedEntry, FetchResponse
from ai_hotspot_ai.storage import StoredArtifact

CRAWL_CONSUMER = "rss-crawl-worker-v1"
CONTENT_CONSUMER = "content-processing-worker-v1"


def database_dsn() -> str:
    return os.getenv(
        "DATABASE_URL",
        "postgresql+psycopg://ai_hotspot:ai_hotspot@localhost:5432/ai_hotspot",
    ).replace("postgresql+psycopg://", "postgresql://", 1)


@dataclass(frozen=True, slots=True)
class FetchContext:
    job_id: uuid.UUID
    endpoint_id: uuid.UUID
    source_entity_id: uuid.UUID
    url: str
    endpoint_type: str
    display_policy: str
    index_policy: str
    config: dict[str, object]
    etag: str | None
    last_modified: str | None
    attempt_no: int
    max_attempts: int
    correlation_id: uuid.UUID
    trace_id: uuid.UUID
    source_name: str
    source_type: str
    source_official_level: str


@dataclass(frozen=True, slots=True)
class ContentContext:
    content_id: uuid.UUID
    raw_entry_id: uuid.UUID
    title: str
    summary: str | None
    source_name: str
    source_official_level: str
    display_policy: str
    index_policy: str
    config: dict[str, object]
    canonical_url: str | None
    source_published_at: datetime | None
    authority_score: float


def begin_inbox(payload: dict[str, object], consumer_name: str) -> bool:
    event_id = uuid.UUID(str(payload["eventId"]))
    idempotency_key = str(payload["idempotencyKey"])
    with (
        psycopg.connect(database_dsn()) as connection,
        connection.cursor(row_factory=dict_row) as cursor,
    ):
        cursor.execute(
            """
            select event_id, status from messaging.consumer_inbox
            where idempotency_key = %s and consumer_name = %s
            for update
            """,
            (idempotency_key, consumer_name),
        )
        existing = cursor.fetchone()
        if existing and existing["status"] == "SUCCEEDED":
            return False
        if existing:
            cursor.execute(
                """
                update messaging.consumer_inbox
                set event_id = %s, status = 'PROCESSING', processed_at = null,
                    result = null, last_error = null
                where idempotency_key = %s and consumer_name = %s
                """,
                (event_id, idempotency_key, consumer_name),
            )
        else:
            cursor.execute(
                """
                insert into messaging.consumer_inbox (
                    event_id, idempotency_key, consumer_name, status, created_at
                ) values (%s, %s, %s, 'PROCESSING', now())
                """,
                (event_id, idempotency_key, consumer_name),
            )
    return True


def complete_inbox(
    payload: dict[str, object], consumer_name: str, result: dict[str, object]
) -> None:
    with psycopg.connect(database_dsn()) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            update messaging.consumer_inbox
            set status = 'SUCCEEDED', processed_at = now(), result = %s, last_error = null
            where idempotency_key = %s and consumer_name = %s
            """,
            (Jsonb(result), str(payload["idempotencyKey"]), consumer_name),
        )


def fail_inbox(payload: dict[str, object], consumer_name: str, error: str) -> None:
    with psycopg.connect(database_dsn()) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            update messaging.consumer_inbox
            set status = 'FAILED', last_error = %s
            where idempotency_key = %s and consumer_name = %s
            """,
            (error[:2000], str(payload["idempotencyKey"]), consumer_name),
        )


def start_fetch(payload: dict[str, object]) -> FetchContext:
    job_id = uuid.UUID(str(payload["payload"]["fetchJobId"]))
    with (
        psycopg.connect(database_dsn()) as connection,
        connection.cursor(row_factory=dict_row) as cursor,
    ):
        cursor.execute(
            """
            update source.fetch_job
            set status = 'RUNNING', attempt_count = attempt_count + 1,
                started_at = coalesce(started_at, now()), updated_at = now()
            where id = %s and status in ('QUEUED', 'WAITING_RETRY')
            returning attempt_count
            """,
            (job_id,),
        )
        attempt = cursor.fetchone()
        if not attempt:
            cursor.execute("select status from source.fetch_job where id = %s", (job_id,))
            status = cursor.fetchone()
            if status and status["status"] == "SUCCEEDED":
                raise AlreadyCompleted
            raise RuntimeError("Fetch job is missing or not runnable")
        cursor.execute(
            """
            select j.id as job_id, j.endpoint_id, e.source_entity_id, e.url,
                   e.endpoint_type, e.display_policy, e.index_policy, e.config,
                   e.last_etag as etag, e.last_modified, j.attempt_count as attempt_no,
                   j.max_attempts, j.correlation_id, j.trace_id,
                   s.name as source_name, s.entity_type as source_type,
                   s.official_level as source_official_level
            from source.fetch_job j
            join source.source_endpoint e on e.id = j.endpoint_id
            join source.source_entity s on s.id = e.source_entity_id
            where j.id = %s
            """,
            (job_id,),
        )
        row = cursor.fetchone()
        if not row:
            raise RuntimeError("Fetch job context was not found")
        return FetchContext(**row)


def finish_not_modified(context: FetchContext, response: FetchResponse) -> None:
    with psycopg.connect(database_dsn()) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            update source.fetch_job
            set status = 'SUCCEEDED', http_status = 304, artifact_count = 0,
                discovered_count = 0, new_entry_count = 0, error_code = null,
                last_error = null, finished_at = now(), updated_at = now()
            where id = %s
            """,
            (context.job_id,),
        )
        cursor.execute(
            """
            update source.source_endpoint
            set health_status = 'HEALTHY', last_success_at = now(), failure_count = 0,
                last_etag = coalesce(%s, last_etag),
                last_modified = coalesce(%s, last_modified),
                last_fetch_item_count = 0, updated_at = now()
            where id = %s
            """,
            (response.etag, response.last_modified, context.endpoint_id),
        )


def persist_feed(
    context: FetchContext,
    response: FetchResponse,
    stored: StoredArtifact,
    content_hash: str,
    entries: list[FeedEntry],
) -> tuple[uuid.UUID, list[uuid.UUID]]:
    artifact_id = uuid.uuid4()
    raw_rows = [
        {
            "id": str(uuid.uuid4()),
            "external_id": entry.external_id,
            "original_url": entry.original_url,
            "canonical_url": entry.canonical_url,
            "raw_title": entry.title,
            "raw_summary": entry.summary,
            "source_published_at": entry.published_at.isoformat() if entry.published_at else None,
            "source_published_at_source": entry.published_at_source,
            "author_name": entry.author_name,
            "payload": entry.payload,
            "entry_hash": entry.entry_hash,
        }
        for entry in entries
    ]
    with (
        psycopg.connect(database_dsn()) as connection,
        connection.cursor(row_factory=dict_row) as cursor,
    ):
        cursor.execute(
            """
            insert into source.fetch_artifact (
                id, fetch_job_id, endpoint_id, attempt_no, request_url, final_url,
                http_status, content_type, content_length, content_hash, etag,
                last_modified, object_bucket, object_key, fetched_at, metadata
            ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now(), %s)
            """,
            (
                artifact_id,
                context.job_id,
                context.endpoint_id,
                context.attempt_no,
                context.url,
                response.final_url,
                response.status_code,
                response.content_type,
                len(response.content),
                content_hash,
                response.etag,
                response.last_modified,
                stored.bucket,
                stored.object_key,
                Jsonb({"connector": context.endpoint_type, "entryCount": len(entries)}),
            ),
        )
        cursor.execute(
            """
            with incoming as (
                select * from jsonb_to_recordset(%s::jsonb) as x(
                    id uuid, external_id text, original_url text, canonical_url text,
                    raw_title text, raw_summary text, source_published_at timestamptz,
                    source_published_at_source text,
                    author_name text, payload jsonb, entry_hash text
                )
            )
            insert into source.raw_entry (
                id, fetch_artifact_id, endpoint_id, external_id, original_url,
                canonical_url, raw_title, raw_summary, source_published_at,
                source_published_at_source,
                author_name, payload, entry_hash, normalization_status
            )
            select id, %s, %s, external_id, original_url, canonical_url,
                   raw_title, raw_summary, source_published_at,
                   source_published_at_source, author_name,
                   payload, entry_hash, 'PENDING'
            from incoming
            on conflict (endpoint_id, external_id) do nothing
            returning id, external_id
            """,
            (Jsonb(raw_rows), artifact_id, context.endpoint_id),
        )
        inserted_raw = cursor.fetchall()
        cursor.execute(
            """
            with incoming as (
                select * from jsonb_to_recordset(%s::jsonb) as x(
                    external_id text, original_url text, canonical_url text,
                    raw_title text, raw_summary text, source_published_at timestamptz,
                    source_published_at_source text,
                    author_name text, payload jsonb, entry_hash text
                )
            )
            update source.raw_entry r
            set last_seen_at = now(), updated_at = now(),
                fetch_artifact_id = case
                    when r.entry_hash <> i.entry_hash then %s else r.fetch_artifact_id end,
                original_url = case
                    when r.entry_hash <> i.entry_hash then i.original_url else r.original_url end,
                canonical_url = case
                    when r.entry_hash <> i.entry_hash then i.canonical_url else r.canonical_url end,
                raw_title = case
                    when r.entry_hash <> i.entry_hash then i.raw_title else r.raw_title end,
                raw_summary = case
                    when r.entry_hash <> i.entry_hash then i.raw_summary else r.raw_summary end,
                source_published_at = coalesce(i.source_published_at, r.source_published_at),
                source_published_at_source = case
                    when i.source_published_at is not null then i.source_published_at_source
                    else r.source_published_at_source end,
                payload = case
                    when r.entry_hash <> i.entry_hash then i.payload else r.payload end,
                entry_hash = i.entry_hash
            from incoming i
            where r.endpoint_id = %s and r.external_id = i.external_id
            """,
            (Jsonb(raw_rows), artifact_id, context.endpoint_id),
        )
        content_rows = [
            {"id": str(uuid.uuid4()), "raw_entry_id": str(row["id"])} for row in inserted_raw
        ]
        content_ids: list[uuid.UUID] = []
        if content_rows:
            cursor.execute(
                """
                with incoming as (
                    select * from jsonb_to_recordset(%s::jsonb) as x(id uuid, raw_entry_id uuid)
                )
                insert into content.content_item (
                    id, raw_entry_id, source_entity_id, endpoint_id, original_url,
                    canonical_url, original_title, content_type, source_type,
                    source_official_level, source_published_at, source_published_at_source,
                    display_policy,
                    index_policy, policy_snapshot
                )
                select i.id, r.id, %s, %s, r.original_url, r.canonical_url,
                       r.raw_title, %s, %s, %s, r.source_published_at,
                       r.source_published_at_source,
                       %s, %s, %s
                from incoming i
                join source.raw_entry r on r.id = i.raw_entry_id
                on conflict (raw_entry_id) do nothing
                returning id, raw_entry_id
                """,
                (
                    Jsonb(content_rows),
                    context.source_entity_id,
                    context.endpoint_id,
                    _content_type(context.endpoint_type),
                    context.source_type,
                    context.source_official_level,
                    context.display_policy,
                    context.index_policy,
                    Jsonb({"endpointConfig": context.config, "version": "m4-v1"}),
                ),
            )
            created_content = cursor.fetchall()
            content_ids = [row["id"] for row in created_content]
            _append_content_events(cursor, context, created_content)
        cursor.execute(
            """
            update content.content_item c
            set source_published_at = r.source_published_at,
                source_published_at_source = r.source_published_at_source,
                updated_at = now()
            from source.raw_entry r
            where c.raw_entry_id = r.id and r.endpoint_id = %s
              and r.source_published_at is not null
              and (c.source_published_at is null
                   or c.source_published_at_source is distinct from r.source_published_at_source)
            """,
            (context.endpoint_id,),
        )
        cursor.execute(
            """
            update knowledge.chunk ch
            set source_published_at = c.source_published_at,
                source_published_at_source = c.source_published_at_source,
                effective_published_at = c.effective_published_at
            from knowledge.document d
            join content.content_item c on c.id = d.content_item_id
            where ch.document_id = d.id and c.endpoint_id = %s
              and c.source_published_at is not null
              and (ch.source_published_at is null
                   or ch.source_published_at_source is distinct from c.source_published_at_source)
            """,
            (context.endpoint_id,),
        )
        cursor.execute(
            """
            update source.fetch_job
            set status = 'SUCCEEDED', http_status = %s, artifact_count = 1,
                discovered_count = %s, new_entry_count = %s, error_code = null,
                last_error = null, finished_at = now(), updated_at = now()
            where id = %s
            """,
            (response.status_code, len(entries), len(inserted_raw), context.job_id),
        )
        cursor.execute(
            """
            update source.source_endpoint
            set health_status = 'HEALTHY', last_success_at = now(), failure_count = 0,
                last_etag = %s, last_modified = %s, last_content_hash = %s,
                last_fetch_item_count = %s, updated_at = now()
            where id = %s
            """,
            (
                response.etag,
                response.last_modified,
                content_hash,
                len(entries),
                context.endpoint_id,
            ),
        )
    return artifact_id, content_ids


def _append_content_events(cursor: psycopg.Cursor, context: FetchContext, rows: list[dict]) -> None:
    now = datetime.now(UTC)
    events = []
    for row in rows:
        event_id = uuid.uuid4()
        content_id = row["id"]
        raw_entry_id = row["raw_entry_id"]
        envelope = {
            "eventId": str(event_id),
            "eventType": "content.processing.requested",
            "eventVersion": 1,
            "aggregateType": "ContentItem",
            "aggregateId": str(content_id),
            "idempotencyKey": f"content:{content_id}:process:m3-v1",
            "correlationId": str(context.correlation_id),
            "traceId": str(context.trace_id),
            "occurredAt": now.isoformat(),
            "producer": "connector-worker",
            "payload": {"contentItemId": str(content_id), "rawEntryId": str(raw_entry_id)},
        }
        events.append(
            {
                "id": str(uuid.uuid4()),
                "event_id": str(event_id),
                "aggregate_id": str(content_id),
                "payload": envelope,
            }
        )
    cursor.execute(
        """
        with incoming as (
            select * from jsonb_to_recordset(%s::jsonb) as x(
                id uuid, event_id uuid, aggregate_id uuid, payload jsonb
            )
        )
        insert into messaging.outbox_event (
            id, event_id, event_type, event_version, aggregate_type,
            aggregate_id, payload, status, retry_count, created_at
        )
        select id, event_id, 'content.processing.requested', 1, 'ContentItem',
               aggregate_id, payload, 'PENDING', 0, now()
        from incoming
        """,
        (Jsonb(events),),
    )


def mark_fetch_failure(payload: dict[str, object], code: str, error: str, retryable: bool) -> bool:
    job_id = uuid.UUID(str(payload["payload"]["fetchJobId"]))
    with (
        psycopg.connect(database_dsn()) as connection,
        connection.cursor(row_factory=dict_row) as cursor,
    ):
        cursor.execute(
            """
            select attempt_count, max_attempts, endpoint_id
            from source.fetch_job where id = %s for update
            """,
            (job_id,),
        )
        job = cursor.fetchone()
        if not job:
            return False
        should_retry = retryable and job["attempt_count"] < job["max_attempts"]
        status = "WAITING_RETRY" if should_retry else "DEAD_LETTERED"
        cursor.execute(
            """
            update source.fetch_job
            set status = %s, error_code = %s, last_error = %s,
                finished_at = case when %s then null else now() end, updated_at = now()
            where id = %s
            """,
            (status, code, error[:2000], should_retry, job_id),
        )
        cursor.execute(
            """
            update source.source_endpoint
            set health_status = case when %s then 'WARNING' else 'FAILED' end,
                last_failure_at = now(), failure_count = failure_count + 1, updated_at = now()
            where id = %s
            """,
            (should_retry, job["endpoint_id"]),
        )
        if not should_retry:
            _insert_dead_letter(
                cursor, payload, "q.crawl.worker", code, error, job["attempt_count"]
            )
    return should_retry


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
        cursor.execute("delete from content.content_topic where content_item_id = %s and assignment_source = 'RULE'", (context.content_id,))
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
                (t.slug='benchmark' and exists(select 1 from content.content_tag tag where tag.content_item_id=c.id and tag.tag='评测基准')) or
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
                generation_metadata = jsonb_build_object('developmentOnly', true, 'publishable', false),
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
                uuid.uuid4(), context.content_id, provider_name, provider_model,
                _fingerprint(f"{context.title}\n{context.summary or ''}"),
            ),
        )
        cursor.execute(
            "update source.raw_entry set normalization_status='NORMALIZED',updated_at=now() where id=%s",
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
    import re

    return set(re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]{2,}", value.lower()))


def _fingerprint(value: str) -> str:
    import hashlib

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
            _insert_dead_letter(cursor, payload, "q.content.worker", code, error, attempt_no)
    return should_retry


def _insert_dead_letter(
    cursor: psycopg.Cursor,
    payload: dict[str, object],
    queue_name: str,
    code: str,
    error: str,
    failure_count: int,
) -> None:
    cursor.execute(
        """
        insert into messaging.dead_letter_record (
            id, original_event_id, queue_name, event_type, payload, failure_count,
            first_failed_at, last_failed_at, last_error, replay_status,
            idempotency_key, aggregate_type, aggregate_id, correlation_id, trace_id
        ) values (%s, %s, %s, %s, %s, %s, now(), now(), %s, 'PENDING', %s, %s, %s, %s, %s)
        on conflict (original_event_id, queue_name) do update
        set failure_count = excluded.failure_count, last_failed_at = now(),
            last_error = excluded.last_error, payload = excluded.payload
        """,
        (
            uuid.uuid4(),
            uuid.UUID(str(payload["eventId"])),
            queue_name,
            str(payload["eventType"]),
            Jsonb(payload),
            max(failure_count, 1),
            f"{code}: {error}"[:2000],
            str(payload.get("idempotencyKey") or ""),
            str(payload.get("aggregateType") or ""),
            uuid.UUID(str(payload["aggregateId"])),
            uuid.UUID(str(payload["correlationId"])),
            uuid.UUID(str(payload["traceId"])),
        ),
    )


class AlreadyCompleted(RuntimeError):
    pass


def _content_type(endpoint_type: str) -> str:
    if endpoint_type in {"ARXIV", "OPENREVIEW", "HUGGING_FACE"}:
        return "RESEARCH"
    if endpoint_type == "GITHUB":
        return "RELEASE"
    return "ARTICLE"
