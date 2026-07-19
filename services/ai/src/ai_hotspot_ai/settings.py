from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    environment: str = Field(default="development", validation_alias="AI_HOTSPOT_ENV")
    version: str = Field(default="0.1.0", validation_alias="AI_HOTSPOT_VERSION")

    generation_provider: Literal["mock", "openai-compatible"] = "mock"
    generation_base_url: str | None = None
    generation_model: str = "deepseek-v4-flash"
    generation_api_key: str | None = None

    embedding_provider: Literal["mock", "remote"] = "mock"
    embedding_base_url: str | None = None
    embedding_model: str = "BAAI/bge-m3"
    embedding_api_key: str | None = None

    rerank_provider: Literal["mock", "remote"] = "mock"
    rerank_base_url: str | None = None
    rerank_model: str = "BAAI/bge-reranker-v2-m3"
    rerank_api_key: str | None = None

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


@lru_cache
def get_settings() -> Settings:
    return Settings()
