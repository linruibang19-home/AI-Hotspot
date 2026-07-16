from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RankedDocument:
    id: str
    score: float


class GenerationProvider(ABC):
    name: str
    model: str

    @abstractmethod
    async def generate(self, prompt: str) -> str: ...


class EmbeddingProvider(ABC):
    name: str
    model: str

    @abstractmethod
    async def embed(self, texts: list[str]) -> list[list[float]]: ...


class RerankProvider(ABC):
    name: str
    model: str

    @abstractmethod
    async def rerank(
        self, query: str, documents: list[tuple[str, str]], top_n: int
    ) -> list[RankedDocument]: ...
