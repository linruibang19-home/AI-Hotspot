import io
from dataclasses import dataclass
from datetime import UTC, datetime

from minio import Minio

from ai_hotspot_ai.settings import Settings


@dataclass(frozen=True, slots=True)
class StoredArtifact:
    bucket: str
    object_key: str


class ArtifactStore:
    def __init__(self, settings: Settings) -> None:
        endpoint = settings.minio_endpoint.removeprefix("http://").removeprefix("https://")
        self.bucket = settings.minio_bucket
        self.client = Minio(
            endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=settings.minio_endpoint.startswith("https://"),
        )

    def put(
        self,
        *,
        endpoint_id: str,
        job_id: str,
        attempt_no: int,
        content: bytes,
        content_type: str | None,
    ) -> StoredArtifact:
        if not self.client.bucket_exists(self.bucket):
            self.client.make_bucket(self.bucket)
        today = datetime.now(UTC)
        object_key = f"rss/{today:%Y/%m/%d}/{endpoint_id}/{job_id}/attempt-{attempt_no}.xml"
        self.client.put_object(
            self.bucket,
            object_key,
            io.BytesIO(content),
            length=len(content),
            content_type=content_type or "application/xml",
        )
        return StoredArtifact(self.bucket, object_key)
