import os
import uuid
from dataclasses import dataclass
from datetime import datetime

import psycopg
from psycopg.types.json import Jsonb


def database_dsn() -> str:
    return os.getenv(
        "DATABASE_URL",
        "postgresql+psycopg://ai_hotspot:ai_hotspot@localhost:5432/ai_hotspot",
    ).replace("postgresql+psycopg://", "postgresql://", 1)


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


class AlreadyCompleted(RuntimeError):
    pass


def insert_dead_letter(
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
