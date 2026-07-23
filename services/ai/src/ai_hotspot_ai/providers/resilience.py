import asyncio
import random
from dataclasses import dataclass

import httpx


@dataclass(frozen=True, slots=True)
class ProviderPolicy:
    connect_timeout_seconds: float
    read_timeout_seconds: float
    total_timeout_seconds: float
    retry_attempts: int = 1


class ProviderError(RuntimeError):
    def __init__(
        self,
        capability: str,
        code: str,
        message: str,
        *,
        retryable: bool,
        attempts: int,
    ) -> None:
        super().__init__(message)
        self.capability = capability
        self.code = code
        self.retryable = retryable
        self.attempts = attempts


async def post_json(
    capability: str,
    url: str,
    *,
    headers: dict[str, str],
    payload: dict[str, object],
    policy: ProviderPolicy,
) -> dict[str, object]:
    attempts = policy.retry_attempts + 1
    timeout = httpx.Timeout(
        connect=policy.connect_timeout_seconds,
        read=policy.read_timeout_seconds,
        write=policy.read_timeout_seconds,
        pool=policy.connect_timeout_seconds,
    )
    deadline = asyncio.get_running_loop().time() + policy.total_timeout_seconds
    for attempt in range(1, attempts + 1):
        cause: Exception | None = None
        try:
            remaining_seconds = deadline - asyncio.get_running_loop().time()
            if remaining_seconds <= 0:
                raise TimeoutError
            async with (
                asyncio.timeout(remaining_seconds),
                httpx.AsyncClient(timeout=timeout) as client,
            ):
                response = await client.post(url, headers=headers, json=payload)
                response.raise_for_status()
                value = response.json()
                if not isinstance(value, dict):
                    raise ProviderError(
                        capability,
                        f"{capability}_INVALID_RESPONSE",
                        "Provider response must be a JSON object",
                        retryable=False,
                        attempts=attempt,
                    )
                return value
        except ProviderError:
            raise
        except (TimeoutError, httpx.TimeoutException) as error:
            cause = error
            provider_error = ProviderError(
                capability,
                f"{capability}_TIMEOUT",
                "Provider request timed out",
                retryable=True,
                attempts=attempt,
            )
        except httpx.HTTPStatusError as error:
            cause = error
            status = error.response.status_code
            retryable = status in {408, 429} or status >= 500
            suffix = "RATE_LIMITED" if status == 429 else f"HTTP_{status}"
            provider_error = ProviderError(
                capability,
                f"{capability}_{suffix}",
                f"Provider returned HTTP {status}",
                retryable=retryable,
                attempts=attempt,
            )
        except httpx.RequestError as error:
            cause = error
            provider_error = ProviderError(
                capability,
                f"{capability}_NETWORK",
                f"Provider network request failed: {type(error).__name__}",
                retryable=True,
                attempts=attempt,
            )
        except (TypeError, ValueError) as error:
            raise ProviderError(
                capability,
                f"{capability}_INVALID_RESPONSE",
                f"Provider returned invalid JSON: {type(error).__name__}",
                retryable=False,
                attempts=attempt,
            ) from error

        if not provider_error.retryable or attempt >= attempts:
            raise provider_error from cause
        await asyncio.sleep(0.1 * attempt + random.uniform(0.0, 0.1))

    raise AssertionError("provider retry loop exhausted")
