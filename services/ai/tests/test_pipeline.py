from ai_hotspot_ai import pipeline
from ai_hotspot_ai.content_repository import _may_publish_unconfirmed


def test_m1_smoke_event_is_completed_without_content_item(monkeypatch) -> None:
    calls: list[tuple[str, object]] = []
    monkeypatch.setattr(pipeline, "begin_inbox", lambda payload, consumer: True)
    monkeypatch.setattr(
        pipeline,
        "complete_inbox",
        lambda payload, consumer, result: calls.append((consumer, result)),
    )
    payload = {
        "eventId": "00000000-0000-0000-0000-000000000001",
        "eventType": "content.processing.smoke",
        "idempotencyKey": "m1:test",
        "payload": {"stage": "M1"},
    }

    result = pipeline.process_content_event(payload, None)  # type: ignore[arg-type]

    assert result == {"stage": "M1", "accepted": True}
    assert calls == [(pipeline.CONTENT_CONSUMER, result)]


def test_duplicate_m1_smoke_event_is_ignored(monkeypatch) -> None:
    monkeypatch.setattr(pipeline, "begin_inbox", lambda payload, consumer: False)
    result = pipeline.process_content_event(
        {"eventType": "content.processing.smoke"},
        None,  # type: ignore[arg-type]
    )
    assert result == {"duplicate": True}


def test_low_authority_unconfirmed_content_requires_review() -> None:
    assert _may_publish_unconfirmed("THIRD_PARTY", 79.99) is False
    assert _may_publish_unconfirmed("THIRD_PARTY", 80) is True
    assert _may_publish_unconfirmed("OFFICIAL", 50) is True
