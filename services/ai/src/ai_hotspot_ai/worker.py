import asyncio
import json
import os

import aio_pika
from aio_pika import DeliveryMode, ExchangeType

from ai_hotspot_ai.feed import FeedError
from ai_hotspot_ai.pipeline import process_content_event, process_crawl_event
from ai_hotspot_ai.repository import (
    CONTENT_CONSUMER,
    CRAWL_CONSUMER,
    fail_inbox,
    mark_content_failure,
    mark_fetch_failure,
)
from ai_hotspot_ai.settings import get_settings

MAIN_EXCHANGE = "aihot.events"
RETRY_EXCHANGE = "aihot.retry"
DLX_EXCHANGE = "aihot.dlx"
ROUTING_KEYS = {
    "q.crawl.worker": "source.crawl.#",
    "q.content.worker": "content.processing.#",
    "q.embedding.worker": "embedding.#",
    "q.cluster.worker": "event.cluster.#",
    "q.report.worker": "report.#",
    "q.notification.worker": "notification.#",
    "q.agent.worker": "agent.run.#",
}
QUEUES = tuple(ROUTING_KEYS)


async def consume_crawl_message(
    message: aio_pika.IncomingMessage, retry_exchange: aio_pika.Exchange
) -> None:
    payload = json.loads(message.body)
    try:
        await asyncio.to_thread(process_crawl_event, payload, get_settings())
        await message.ack()
    except Exception as exception:
        error = str(exception) or exception.__class__.__name__
        code = exception.code if isinstance(exception, FeedError) else exception.__class__.__name__
        retryable = exception.retryable if isinstance(exception, FeedError) else True
        try:
            should_retry = await asyncio.to_thread(
                mark_fetch_failure, payload, code, error, retryable
            )
            await asyncio.to_thread(fail_inbox, payload, CRAWL_CONSUMER, error)
        except Exception:
            await message.nack(requeue=True)
            return
        if should_retry:
            await _publish_retry(
                retry_exchange, "q.crawl.worker.retry", message, _attempt(message) + 1
            )
            await message.ack()
        else:
            await message.reject(requeue=False)


async def consume_content_message(
    message: aio_pika.IncomingMessage, retry_exchange: aio_pika.Exchange
) -> None:
    payload = json.loads(message.body)
    attempt_no = _attempt(message) + 1
    max_attempts = 4
    try:
        await asyncio.to_thread(process_content_event, payload, get_settings())
        await message.ack()
    except Exception as exception:
        error = str(exception) or exception.__class__.__name__
        code = exception.__class__.__name__
        try:
            should_retry = await asyncio.to_thread(
                mark_content_failure, payload, code, error, attempt_no, max_attempts
            )
            await asyncio.to_thread(fail_inbox, payload, CONTENT_CONSUMER, error)
        except Exception:
            await message.nack(requeue=True)
            return
        if should_retry:
            await _publish_retry(retry_exchange, "q.content.worker.retry", message, attempt_no)
            await message.ack()
        else:
            await message.reject(requeue=False)


async def _publish_retry(
    exchange: aio_pika.Exchange,
    routing_key: str,
    original: aio_pika.IncomingMessage,
    attempt: int,
) -> None:
    headers = dict(original.headers or {})
    headers["x-aihotspot-retry-count"] = attempt
    await exchange.publish(
        aio_pika.Message(
            body=original.body,
            content_type="application/json",
            delivery_mode=DeliveryMode.PERSISTENT,
            message_id=original.message_id,
            correlation_id=original.correlation_id,
            headers=headers,
        ),
        routing_key=routing_key,
    )


def _attempt(message: aio_pika.IncomingMessage) -> int:
    try:
        return int((message.headers or {}).get("x-aihotspot-retry-count", 0))
    except (TypeError, ValueError):
        return 0


async def serve() -> None:
    settings = get_settings()
    connection = await aio_pika.connect_robust(
        os.getenv("RABBITMQ_URL", "amqp://ai_hotspot:ai_hotspot@localhost:5672/ai_hotspot")
    )
    async with connection:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=4)
        main_exchange = await channel.declare_exchange(
            MAIN_EXCHANGE, ExchangeType.TOPIC, durable=True
        )
        retry_exchange = await channel.declare_exchange(
            RETRY_EXCHANGE, ExchangeType.DIRECT, durable=True
        )
        dead_letter_exchange = await channel.declare_exchange(
            DLX_EXCHANGE, ExchangeType.DIRECT, durable=True
        )
        declared: dict[str, aio_pika.Queue] = {}
        for queue_name, routing_key in ROUTING_KEYS.items():
            queue = await channel.declare_queue(
                queue_name,
                durable=True,
                arguments={
                    "x-dead-letter-exchange": DLX_EXCHANGE,
                    "x-dead-letter-routing-key": queue_name,
                },
            )
            await queue.bind(main_exchange, routing_key)
            dead_letter_queue = await channel.declare_queue(f"{queue_name}.dlq", durable=True)
            await dead_letter_queue.bind(dead_letter_exchange, queue_name)
            declared[queue_name] = queue
        await _declare_retry_queue(
            channel,
            retry_exchange,
            "q.crawl.worker.retry",
            "source.crawl.requested",
            settings.crawl_retry_delay_ms,
        )
        await _declare_retry_queue(
            channel,
            retry_exchange,
            "q.content.worker.retry",
            "content.processing.requested",
            settings.content_retry_delay_ms,
        )
        await declared["q.crawl.worker"].consume(
            lambda message: consume_crawl_message(message, retry_exchange)
        )
        await declared["q.content.worker"].consume(
            lambda message: consume_content_message(message, retry_exchange)
        )
        await asyncio.Future()


async def _declare_retry_queue(
    channel: aio_pika.Channel,
    exchange: aio_pika.Exchange,
    queue_name: str,
    return_routing_key: str,
    delay_ms: int,
) -> None:
    queue = await channel.declare_queue(
        queue_name,
        durable=True,
        arguments={
            "x-message-ttl": max(delay_ms, 100),
            "x-dead-letter-exchange": MAIN_EXCHANGE,
            "x-dead-letter-routing-key": return_routing_key,
        },
    )
    await queue.bind(exchange, queue_name)


def run() -> None:
    asyncio.run(serve())
