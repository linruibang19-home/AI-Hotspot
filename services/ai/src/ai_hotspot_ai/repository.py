import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ai_hotspot_ai.feed import FeedEntry, FetchResponse
from ai_hotspot_ai.repository_common import (
    AlreadyCompleted,
    database_dsn,
    insert_dead_letter,
)
from ai_hotspot_ai.storage import StoredArtifact

CRAWL_CONSUMER = "rss-crawl-worker-v1"
CONTENT_CONSUMER = "content-processing-worker-v1"


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
                poll_outcome = 'PENDING',
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
                poll_outcome = 'NOT_MODIFIED', last_error = null,
                finished_at = now(), updated_at = now()
            where id = %s
            """,
            (context.job_id,),
        )
        cursor.execute(
            """
            update source.source_endpoint
            set health_status = 'HEALTHY', last_success_at = now(), failure_count = 0,
                last_poll_outcome = 'NOT_MODIFIED',
                consecutive_no_change_count = consecutive_no_change_count + 1,
                last_etag = coalesce(%s, last_etag),
                last_modified = coalesce(%s, last_modified),
                last_fetch_item_count = 0, updated_at = now()
            where id = %s
            """,
            (response.etag, response.last_modified, context.endpoint_id),
        )
        _reconcile_endpoint_dead_letters(cursor, context.endpoint_id)


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
                poll_outcome = case when %s > 0 then 'NEW_CONTENT' else 'NO_NEW_CONTENT' end,
                last_error = null, finished_at = now(), updated_at = now()
            where id = %s
            """,
            (
                response.status_code,
                len(entries),
                len(inserted_raw),
                len(inserted_raw),
                context.job_id,
            ),
        )
        cursor.execute(
            """
            update source.source_endpoint
            set health_status = 'HEALTHY', last_success_at = now(), failure_count = 0,
                last_poll_outcome = case
                    when %s > 0 then 'NEW_CONTENT' else 'NO_NEW_CONTENT' end,
                consecutive_no_change_count = case
                    when %s > 0 then 0 else consecutive_no_change_count + 1 end,
                last_etag = %s, last_modified = %s, last_content_hash = %s,
                last_fetch_item_count = %s, updated_at = now()
            where id = %s
            """,
            (
                len(inserted_raw),
                len(inserted_raw),
                response.etag,
                response.last_modified,
                content_hash,
                len(entries),
                context.endpoint_id,
            ),
        )
        _reconcile_endpoint_dead_letters(cursor, context.endpoint_id)
    return artifact_id, content_ids


def _reconcile_endpoint_dead_letters(cursor: psycopg.Cursor, endpoint_id: uuid.UUID) -> None:
    """Keep recovered crawl failures auditable without blocking readiness."""
    cursor.execute(
        """
        update messaging.dead_letter_record
        set replay_status = 'IGNORED'
        where replay_status = 'PENDING'
          and event_type = 'source.crawl.requested'
          and payload #>> '{payload,endpointId}' = %s
        """,
        (str(endpoint_id),),
    )


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
        poll_outcome = _failure_poll_outcome(code)
        cursor.execute(
            """
            update source.fetch_job
            set status = %s, poll_outcome = %s, error_code = %s, last_error = %s,
                finished_at = case when %s then null else now() end, updated_at = now()
            where id = %s
            """,
            (status, poll_outcome, code, error[:2000], should_retry, job_id),
        )
        cursor.execute(
            """
            update source.source_endpoint
            set health_status = case when %s then 'WARNING' else 'FAILED' end,
                last_poll_outcome = %s, consecutive_no_change_count = 0,
                last_failure_at = now(), failure_count = failure_count + 1, updated_at = now()
            where id = %s
            """,
            (should_retry, poll_outcome, job["endpoint_id"]),
        )
        if not should_retry:
            insert_dead_letter(cursor, payload, "q.crawl.worker", code, error, job["attempt_count"])
    return should_retry


def _failure_poll_outcome(code: str) -> str:
    if code in {
        "DNS_FAILURE",
        "NETWORK_FAILURE",
        "UPSTREAM_REJECTED",
        "UPSTREAM_TEMPORARY",
        "INVALID_REDIRECT",
        "TOO_MANY_REDIRECTS",
    }:
        return "UPSTREAM_FAILURE"
    return "CONTENT_FAILURE"


def _content_type(endpoint_type: str) -> str:
    if endpoint_type in {"ARXIV", "OPENREVIEW", "HUGGING_FACE"}:
        return "RESEARCH"
    if endpoint_type == "GITHUB":
        return "RELEASE"
    return "ARTICLE"
