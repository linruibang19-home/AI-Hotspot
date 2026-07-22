from httpx import ASGITransport, AsyncClient

from ai_hotspot_ai.main import app
from ai_hotspot_ai.worker import QUEUES, ROUTING_KEYS


async def request(method: str, path: str, **kwargs):
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        return await client.request(method, path, **kwargs)


async def test_health_and_correlation_id() -> None:
    response = await request("GET", "/health", headers={"x-correlation-id": "test-correlation"})
    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.headers["x-correlation-id"] == "test-correlation"


async def test_mock_provider_flow_is_deterministic() -> None:
    providers = (await request("GET", "/api/v1/providers")).json()
    first = (await request("POST", "/api/v1/mock/embed", json={"texts": ["AI Hotspot"]})).json()
    second = (await request("POST", "/api/v1/mock/embed", json={"texts": ["AI Hotspot"]})).json()
    generated = (await request("POST", "/api/v1/mock/generate", json={"prompt": "生成摘要", "max_tokens": 256})).json()

    assert providers["generation"]["provider"] == "mock"
    assert first["vectors"] == second["vectors"]
    assert len(first["vectors"][0]) == 16
    assert generated["text"].startswith("[Mock Provider]")
    assert "input_tokens" in generated
    assert "output_tokens" in generated


async def test_mock_rerank_orders_by_overlap() -> None:
    response = await request(
        "POST",
        "/api/v1/mock/rerank",
        json={
            "query": "Agent 评测",
            "documents": [
                {"id": "irrelevant", "text": "数据库备份"},
                {"id": "relevant", "text": "Agent 评测与审计"},
            ],
            "top_n": 2,
        },
    )
    assert response.status_code == 200
    assert response.json()["results"][0]["id"] == "relevant"


def test_every_worker_queue_has_a_routing_key() -> None:
    assert set(QUEUES) == set(ROUTING_KEYS)
    assert ROUTING_KEYS["q.content.worker"] == "content.processing.#"
