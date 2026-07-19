import httpx

from .base import EmbeddingProvider, RankedDocument, RerankProvider


class RemoteEmbeddingProvider(EmbeddingProvider):
    name = "remote"

    def __init__(self, base_url: str, api_key: str | None, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model

    async def embed(self, texts: list[str]) -> list[list[float]]:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        async with httpx.AsyncClient(timeout=60) as client:
            response = await client.post(
                f"{self.base_url}/embeddings", headers=headers,
                json={"model": self.model, "input": texts},
            )
            response.raise_for_status()
            rows = sorted(response.json()["data"], key=lambda row: row.get("index", 0))
            return [list(map(float, row["embedding"])) for row in rows]


class RemoteRerankProvider(RerankProvider):
    name = "remote"

    def __init__(self, base_url: str, api_key: str | None, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model

    async def rerank(self, query: str, documents: list[tuple[str, str]], top_n: int) -> list[RankedDocument]:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        async with httpx.AsyncClient(timeout=60) as client:
            response = await client.post(
                f"{self.base_url}/rerank", headers=headers,
                json={"model": self.model, "query": query,
                      "documents": [text for _, text in documents], "top_n": top_n},
            )
            response.raise_for_status()
            data = response.json()
            results = data.get("results", data.get("data", []))
            ranked = []
            for row in results:
                index = int(row.get("index", row.get("document_index", 0)))
                if 0 <= index < len(documents):
                    ranked.append(RankedDocument(id=documents[index][0], score=float(row.get("relevance_score", row.get("score", 0)))))
            return ranked[:top_n]
