from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RankedDocument:
    id: str
    score: float


@dataclass(frozen=True, slots=True)
class GenerationOutput:
    text: str
    input_tokens: int | None = None
    output_tokens: int | None = None


class GenerationProvider(ABC):
    name: str
    model: str

    @abstractmethod
    async def generate(self, prompt: str, max_tokens: int | None = None) -> str: ...

    async def generate_with_usage(
        self,
        prompt: str,
        max_tokens: int | None = None,
        *,
        system_prompt: str | None = None,
        evidence: str | None = None,
    ) -> GenerationOutput:
        parts = [value for value in (system_prompt, prompt, evidence) if value]
        return GenerationOutput(text=await self.generate("\n\n".join(parts), max_tokens))


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
