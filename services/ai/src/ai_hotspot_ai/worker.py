import asyncio
import json
import os
import uuid

import aio_pika
import psycopg
from aio_pika import ExchangeType

MAIN_EXCHANGE = "aihot.events"
DLX_EXCHANGE = "aihot.dlx"
QUEUES = (
    "q.crawl.worker",
    "q.content.worker",
    "q.embedding.worker",
    "q.cluster.worker",
    "q.report.worker",
    "q.notification.worker",
    "q.agent.worker",
)
ROUTING_KEYS = {
    "q.crawl.worker": "source.crawl.#",
    "q.content.worker": "content.processing.#",
    "q.embedding.worker": "embedding.#",
    "q.cluster.worker": "event.cluster.#",
    "q.report.worker": "report.#",
    "q.notification.worker": "notification.#",
    "q.agent.worker": "agent.run.#",
}
CONSUMER_NAME = "ai-content-worker"


def _database_dsn() -> str:
    return os.getenv(
        "DATABASE_URL",
        "postgresql+psycopg://ai_hotspot:ai_hotspot@localhost:5432/ai_hotspot",
    ).replace("postgresql+psycopg://", "postgresql://", 1)


def persist_inbox(payload: dict[str, object]) -> bool:
    event_id = uuid.UUID(str(payload["eventId"]))
    idempotency_key = str(payload["idempotencyKey"])
    result = json.dumps({"stage": "M1", "accepted": True})
    with psycopg.connect(_database_dsn()) as connection, connection.cursor() as cursor:
        cursor.execute(
            """
            insert into messaging.consumer_inbox (
                event_id, idempotency_key, consumer_name, status,
                processed_at, result, created_at
            ) values (%s, %s, %s, 'SUCCEEDED', now(), %s::jsonb, now())
            on conflict do nothing
            """,
            (event_id, idempotency_key, CONSUMER_NAME, result),
        )
        return cursor.rowcount == 1


async def consume_content_message(message: aio_pika.IncomingMessage) -> None:
    async with message.process(requeue=False):
        payload = json.loads(message.body)
        await asyncio.to_thread(persist_inbox, payload)


async def serve() -> None:
    connection = await aio_pika.connect_robust(
        os.getenv("RABBITMQ_URL", "amqp://ai_hotspot:ai_hotspot@localhost:5672/ai_hotspot")
    )
    async with connection:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=4)
        main_exchange = await channel.declare_exchange(
            MAIN_EXCHANGE, ExchangeType.TOPIC, durable=True
        )
        dead_letter_exchange = await channel.declare_exchange(
            DLX_EXCHANGE, ExchangeType.DIRECT, durable=True
        )
        content_queue = None
        for queue_name in QUEUES:
            queue = await channel.declare_queue(
                queue_name,
                durable=True,
                arguments={
                    "x-dead-letter-exchange": DLX_EXCHANGE,
                    "x-dead-letter-routing-key": queue_name,
                },
            )
            await queue.bind(main_exchange, ROUTING_KEYS[queue_name])

            dead_letter_queue = await channel.declare_queue(f"{queue_name}.dlq", durable=True)
            await dead_letter_queue.bind(dead_letter_exchange, queue_name)
            if queue_name == "q.content.worker":
                content_queue = queue
        if content_queue is None:
            raise RuntimeError("content worker queue was not declared")
        await content_queue.consume(consume_content_message)
        await asyncio.Future()


def run() -> None:
    asyncio.run(serve())
