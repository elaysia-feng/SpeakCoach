"""逐节点审计日志 —— 投递到 Java 的 `/api/internal/audit/ingest` 端点。

D3.8：把每个节点的输入/输出/延迟/模型/prompt 版本 POST 到 Java，
由 Java 侧持久化到 audit_log 表。所有调用都是 fire-and-forget：
- 异步后台任务：不会 await 到主链路上；
- 2s 短超时：网络抖动时立刻放弃，绝不阻塞节点返回；
- try/except 全包裹：任何序列化/网络错误都只记 warning，不抛回调用方。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_DEFAULT_JAVA_BASE = os.environ.get("JAVA_GATEWAY_URL", "http://localhost:8080")
_INTERNAL_TOKEN_ENV = "INTERNAL_API_TOKEN"
_TIMEOUT_SEC = 2.0


async def write_audit(
    node_name: str,
    user_id: str,
    session_id: str,
    turn_id: int,
    input_data: dict[str, Any] | None = None,
    output_data: dict[str, Any] | None = None,
    latency_ms: int = 0,
    model_name: str | None = None,
    prompt_version: str | None = None,
) -> None:
    """审计写入器 —— fire-and-forget，失败不抛回。

    行为：
      1. 立即把事件 enqueue 到后台协程，主调用方立即返回；
      2. 后台协程串行化入/出参数，POST 到 Java `/api/internal/audit/ingest`；
      3. 任何错误（网络、序列化、HTTP 4xx/5xx）都只记 warning。
    """
    # 先把 input/output 预序列化为 JSON 字符串，避免 Java 侧二次解析带来的歧义。
    try:
        input_json = json.dumps(input_data, ensure_ascii=False) if input_data is not None else None
    except (TypeError, ValueError):
        input_json = None
    try:
        output_json = json.dumps(output_data, ensure_ascii=False) if output_data is not None else None
    except (TypeError, ValueError):
        output_json = None

    payload: dict[str, Any] = {
        "userId": _maybe_long(user_id),
        "sessionId": session_id or "",
        "turnId": int(turn_id) if turn_id is not None else 0,
        "nodeName": node_name,
        "inputJson": input_json,
        "outputJson": output_json,
        "latencyMs": int(latency_ms) if latency_ms is not None else 0,
        "modelName": model_name,
        "promptVersion": prompt_version,
    }

    # fire-and-forget —— 主链不 await 网络往返。
    try:
        loop = asyncio.get_running_loop()
        loop.create_task(_post_audit(payload))
    except RuntimeError:
        # 没有运行中的 event loop（例如纯同步测试）—— 退化为同步 POST。
        try:
            _post_audit_sync(payload)
        except Exception as exc:  # noqa: BLE001
            logger.warning("audit (sync fallback) failed: %s", exc)
    except Exception as exc:  # noqa: BLE001
        logger.warning("audit enqueue failed: %s", exc)


async def _post_audit(payload: dict[str, Any]) -> None:
    """后台协程：把审计事件 POST 到 Java。"""
    base_url = os.environ.get("JAVA_GATEWAY_URL", _DEFAULT_JAVA_BASE).rstrip("/")
    token = os.environ.get(_INTERNAL_TOKEN_ENV, "")
    headers = {"X-Internal-Token": token} if token else {}
    url = f"{base_url}/api/internal/audit/ingest"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_SEC) as client:
            resp = await client.post(url, json=payload, headers=headers)
        if resp.status_code >= 400:
            logger.warning(
                "audit ingest returned status=%s body=%s",
                resp.status_code, _truncate(resp.text),
            )
    except (httpx.HTTPError, asyncio.TimeoutError) as exc:
        logger.warning("audit ingest failed: %s", exc)
    except Exception as exc:  # noqa: BLE001
        logger.warning("audit ingest unexpected error: %s", exc)


def _post_audit_sync(payload: dict[str, Any]) -> None:
    """同步回退：测试 / 脚本场景使用。"""
    base_url = os.environ.get("JAVA_GATEWAY_URL", _DEFAULT_JAVA_BASE).rstrip("/")
    token = os.environ.get(_INTERNAL_TOKEN_ENV, "")
    headers = {"X-Internal-Token": token} if token else {}
    url = f"{base_url}/api/internal/audit/ingest"
    with httpx.Client(timeout=_TIMEOUT_SEC) as client:
        resp = client.post(url, json=payload, headers=headers)
    if resp.status_code >= 400:
        logger.warning(
            "audit ingest (sync) returned status=%s body=%s",
            resp.status_code, _truncate(resp.text),
        )


def _maybe_long(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _truncate(s: str, limit: int = 200) -> str:
    if not s:
        return ""
    if len(s) <= limit:
        return s
    return s[:limit] + "..."
