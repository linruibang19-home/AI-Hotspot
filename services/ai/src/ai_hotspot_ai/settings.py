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
    generation_model: str = "deepseek-chat"
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


@lru_cache
def get_settings() -> Settings:
    return Settings()
