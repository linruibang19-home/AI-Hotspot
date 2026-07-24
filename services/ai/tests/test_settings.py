import pytest
from pydantic import ValidationError

from ai_hotspot_ai.settings import Settings


def production_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "AI_HOTSPOT_ENV": "production",
        "generation_provider": "openai-compatible",
        "generation_base_url": "https://generation.example.com/v1",
        "generation_api_key": "generation-test-key-2026",
        "embedding_provider": "remote",
        "embedding_base_url": "https://embedding.example.com/v1",
        "embedding_api_key": "embedding-test-key-2026",
        "rerank_provider": "remote",
        "rerank_base_url": "https://rerank.example.com/v1",
        "rerank_api_key": "rerank-test-key-2026",
        "database_url": "postgresql+psycopg://app:strong-db-password@postgres/prod",
        "redis_url": "redis://:strong-redis-password@redis:6379/0",
        "rabbitmq_url": "amqp://app:strong-rabbit-password@rabbitmq:5672/prod",
        "minio_access_key": "production-app",
        "minio_secret_key": "strong-minio-password-2026",
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_production_accepts_real_providers_and_rotated_credentials() -> None:
    settings = production_settings()

    assert settings.environment == "production"
    assert settings.generation_provider == "openai-compatible"


@pytest.mark.parametrize(
    ("field", "value", "expected"),
    [
        ("generation_provider", "mock", "GENERATION_PROVIDER"),
        ("embedding_api_key", "", "EMBEDDING_API_KEY"),
        ("rerank_base_url", "http://rerank.example.com", "RERANK_BASE_URL"),
        (
            "database_url",
            "postgresql+psycopg://ai_hotspot:ai_hotspot@postgres/prod",
            "DATABASE_URL",
        ),
        ("minio_secret_key", "ai-hotspot-local-password", "MINIO_SECRET_KEY"),
    ],
)
def test_production_rejects_unsafe_provider_or_infrastructure_settings(
    field: str,
    value: str,
    expected: str,
) -> None:
    with pytest.raises(ValidationError) as raised:
        production_settings(**{field: value})

    message = str(raised.value)
    assert expected in message
    assert "ai_hotspot:ai_hotspot@" not in message
