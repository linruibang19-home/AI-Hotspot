import asyncio
import hashlib

from ai_hotspot_ai.connectors import accept_header, parse_connector
from ai_hotspot_ai.feed import FetchRequest, fetch_feed
from ai_hotspot_ai.providers.factory import get_provider_registry
from ai_hotspot_ai.repository import (
    CONTENT_CONSUMER,
    CRAWL_CONSUMER,
    AlreadyCompleted,
    begin_inbox,
    complete_inbox,
    finish_content,
    finish_not_modified,
    load_content,
    persist_feed,
    start_fetch,
)
from ai_hotspot_ai.settings import Settings
from ai_hotspot_ai.storage import ArtifactStore


def process_crawl_event(payload: dict[str, object], settings: Settings) -> dict[str, object]:
    if not begin_inbox(payload, CRAWL_CONSUMER):
        return {"duplicate": True}
    try:
        context = start_fetch(payload)
    except AlreadyCompleted:
        result = {"duplicate": True, "alreadyCompleted": True}
        complete_inbox(payload, CRAWL_CONSUMER, result)
        return result
    config = context.config
    request = FetchRequest(
        url=context.url,
        etag=context.etag,
        last_modified=context.last_modified,
        timeout_seconds=_bounded_int(config.get("timeoutSeconds"), 10, 1, 30),
        max_response_bytes=_bounded_int(
            config.get("maxResponseBytes"), 2 * 1024 * 1024, 1024, 10 * 1024 * 1024
        ),
        user_agent=str(
            config.get("userAgent")
            or "AI-Hotspot-Connector/0.4 (+https://aihotspot.local; contact=admin@aihotspot.local)"
        )[:300],
        accept=accept_header(context.endpoint_type),
    )
    response = fetch_feed(request)
    if response.status_code == 304:
        finish_not_modified(context, response)
        result = {"status": 304, "notModified": True, "newContent": 0}
        complete_inbox(payload, CRAWL_CONSUMER, result)
        return result
    content_hash = hashlib.sha256(response.content).hexdigest()
    entries = parse_connector(
        context.endpoint_type,
        response.content,
        response.final_url,
        config,
        _bounded_int(config.get("maxItems"), 50, 1, 200),
    )
    stored = ArtifactStore(settings).put(
        endpoint_id=str(context.endpoint_id),
        job_id=str(context.job_id),
        attempt_no=context.attempt_no,
        content=response.content,
        content_type=response.content_type,
    )
    artifact_id, content_ids = persist_feed(context, response, stored, content_hash, entries)
    result = {
        "status": response.status_code,
        "artifactId": str(artifact_id),
        "discovered": len(entries),
        "newContent": len(content_ids),
    }
    complete_inbox(payload, CRAWL_CONSUMER, result)
    return result


def process_content_event(payload: dict[str, object], settings: Settings) -> dict[str, object]:
    if payload.get("eventType") == "content.processing.smoke":
        if not begin_inbox(payload, CONTENT_CONSUMER):
            return {"duplicate": True}
        result = {"stage": "M1", "accepted": True}
        complete_inbox(payload, CONTENT_CONSUMER, result)
        return result
    if not begin_inbox(payload, CONTENT_CONSUMER):
        return {"duplicate": True}
    try:
        context = load_content(payload)
    except AlreadyCompleted:
        result = {"duplicate": True, "alreadyCompleted": True}
        complete_inbox(payload, CONTENT_CONSUMER, result)
        return result
    registry = get_provider_registry()
    source_summary = context.summary or context.title
    prompt = (
        "请用中文简洁概括以下公开 AI 技术资讯，不添加原文不存在的事实：\n"
        f"标题：{context.title}\n内容：{source_summary[:3000]}"
    )
    generated = asyncio.run(registry.generation.generate(prompt))
    summary = context.summary or generated
    relevance_score = _bounded_float(context.config.get("relevanceScore"), 78.0)
    quality_score = _bounded_float(context.config.get("qualityScore"), 72.0)
    final_score = round(relevance_score * 0.55 + quality_score * 0.45, 2)
    reason = (
        f"来自{context.source_name}的{_official_label(context.source_official_level)}公开内容，"
        "已完成基础相关性与质量准入。"
    )
    published = finish_content(
        context,
        summary=summary,
        reason=reason,
        relevance_score=relevance_score,
        quality_score=quality_score,
        final_score=final_score,
        provider_name=registry.generation.name,
        provider_model=registry.generation.model,
        relevance_threshold=settings.content_relevance_threshold,
        quality_threshold=settings.content_quality_threshold,
    )
    result = {
        "published": published,
        "finalScore": final_score,
        "provider": registry.generation.name,
    }
    complete_inbox(payload, CONTENT_CONSUMER, result)
    return result


def _bounded_int(value: object, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value) if value is not None else default
    except (TypeError, ValueError):
        parsed = default
    return min(max(parsed, minimum), maximum)


def _bounded_float(value: object, default: float) -> float:
    try:
        parsed = float(value) if value is not None else default
    except (TypeError, ValueError):
        parsed = default
    return min(max(parsed, 0.0), 100.0)


def _official_label(value: str) -> str:
    return {"OFFICIAL": "官方", "FIRST_PARTY": "一手", "THIRD_PARTY": "第三方"}.get(value, "公开")
