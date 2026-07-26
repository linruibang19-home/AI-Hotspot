from functools import lru_cache
from typing import Literal, Self
from urllib.parse import urlsplit

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        hide_input_in_errors=True,
    )

    environment: str = Field(default="development", validation_alias="AI_HOTSPOT_ENV")
    version: str = Field(default="0.1.0", validation_alias="AI_HOTSPOT_VERSION")

    generation_provider: Literal["mock", "openai-compatible"] = "mock"
    generation_base_url: str | None = None
    generation_model: str = "deepseek-v4-flash"
    generation_api_key: str | None = None
    generation_connect_timeout_seconds: float = Field(default=3.0, gt=0, le=30)
    generation_read_timeout_seconds: float = Field(default=35.0, gt=0, le=120)
    generation_total_timeout_seconds: float = Field(default=40.0, gt=0, le=180)

    embedding_provider: Literal["mock", "remote"] = "mock"
    embedding_base_url: str | None = None
    embedding_model: str = "BAAI/bge-m3"
    embedding_api_key: str | None = None
    embedding_connect_timeout_seconds: float = Field(default=3.0, gt=0, le=30)
    embedding_read_timeout_seconds: float = Field(default=15.0, gt=0, le=120)
    embedding_total_timeout_seconds: float = Field(default=20.0, gt=0, le=180)

    rerank_provider: Literal["mock", "remote"] = "mock"
    rerank_base_url: str | None = None
    rerank_model: str = "BAAI/bge-reranker-v2-m3"
    rerank_api_key: str | None = None
    rerank_connect_timeout_seconds: float = Field(default=3.0, gt=0, le=30)
    rerank_read_timeout_seconds: float = Field(default=15.0, gt=0, le=120)
    rerank_total_timeout_seconds: float = Field(default=20.0, gt=0, le=180)
    provider_retry_attempts: int = Field(default=1, ge=0, le=1)
    ai_internal_api_token: str = "ai-hotspot-local-internal-token"

    database_url: str = "postgresql+psycopg://ai_hotspot:ai_hotspot@localhost:5432/ai_hotspot"
    redis_url: str = "redis://localhost:6379/0"
    rabbitmq_url: str = "amqp://ai_hotspot:ai_hotspot@localhost:5672/ai_hotspot"
    minio_endpoint: str = "http://localhost:9000"
    minio_access_key: str = "ai_hotspot"
    minio_secret_key: str = "ai_hotspot-local-password"
    minio_bucket: str = "fetch-artifacts"

    crawl_retry_delay_ms: int = 60_000
    content_retry_delay_ms: int = 60_000
    content_relevance_threshold: float = 70.0
    content_quality_threshold: float = 60.0

    @model_validator(mode="after")
    def validate_production_security(self) -> Self:
        if self.environment.strip().lower() not in {"prod", "production"}:
            return self

        violations: list[str] = []
        if (
            len(self.ai_internal_api_token.strip()) < 32
            or self.ai_internal_api_token == "ai-hotspot-local-internal-token"
        ):
            violations.append("AI_INTERNAL_API_TOKEN must be replaced with a strong value")
        self._require_real_provider(
            violations,
            "GENERATION",
            self.generation_provider,
            self.generation_base_url,
            self.generation_api_key,
        )
        self._require_real_provider(
            violations,
            "EMBEDDING",
            self.embedding_provider,
            self.embedding_base_url,
            self.embedding_api_key,
        )
        self._require_real_provider(
            violations,
            "RERANK",
            self.rerank_provider,
            self.rerank_base_url,
            self.rerank_api_key,
        )
        self._reject_weak_url(
            violations,
            "DATABASE_URL",
            self.database_url,
            ("localhost", "ai_hotspot:ai_hotspot@"),
        )
        self._reject_weak_url(
            violations,
            "REDIS_URL",
            self.redis_url,
            ("localhost", "redis://redis:6379", ":ai_hotspot@"),
        )
        self._reject_weak_url(
            violations,
            "RABBITMQ_URL",
            self.rabbitmq_url,
            ("localhost", "ai_hotspot:ai_hotspot@"),
        )
        if len(self.minio_access_key.strip()) < 8 or self.minio_access_key == "ai_hotspot":
            violations.append("MINIO_ACCESS_KEY must be replaced")
        if len(self.minio_secret_key.strip()) < 16 or self.minio_secret_key in {
            "ai_hotspot",
            "ai-hotspot-local-password",
            "change-me-in-local-env",
        }:
            violations.append("MINIO_SECRET_KEY must be replaced with a strong value")

        if violations:
            raise ValueError(
                "Production security validation failed: " + ", ".join(violations)
            )
        return self

    @staticmethod
    def _require_real_provider(
        violations: list[str],
        prefix: str,
        provider: str,
        base_url: str | None,
        api_key: str | None,
    ) -> None:
        if provider == "mock":
            violations.append(f"{prefix}_PROVIDER must not be mock")
        parsed = urlsplit(base_url or "")
        if parsed.scheme.lower() != "https" or not parsed.hostname:
            violations.append(f"{prefix}_BASE_URL must be an absolute HTTPS URL")
        if len((api_key or "").strip()) < 16:
            violations.append(f"{prefix}_API_KEY must be configured")

    @staticmethod
    def _reject_weak_url(
        violations: list[str],
        field: str,
        value: str,
        weak_fragments: tuple[str, ...],
    ) -> None:
        lowered = value.lower()
        if any(fragment in lowered for fragment in weak_fragments):
            violations.append(f"{field} contains a local or weak default")


@lru_cache
def get_settings() -> Settings:
    return Settings()
