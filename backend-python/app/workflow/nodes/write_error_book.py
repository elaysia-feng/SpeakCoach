"""write_error_book 节点 —— 把当前 turn 的 corrections 批量写入 Java 错题本。

设计要点：
- 不阻断 /api/chat 主链：任何 HTTP / 网络失败都旁路，仅记日志。
- 失败降级：返回原 state，不抛错。
- 三元组去重：在 Java 端用 UNIQUE 约束保证；本节点不做预去重。
"""

from __future__ import annotations

import logging
import os
import time
from typing import Any

import httpx

from ...audit import write_audit
from ...config import get_settings
from ..state import AgentState, Correction

logger = logging.getLogger(__name__)

# 默认与 Java application.yml 中的 app.internal.shared-secret 默认值保持一致。
# 任何生产部署都必须通过 INTERNAL_SHARED_SECRET 显式注入。
_DEFAULT_SHARED_SECRET = "speakcoach-internal-dev-secret"


def _java_base_url() -> str:
    """Java 网关的 base URL —— 优先用环境变量，否则用开发默认值。"""
    explicit = os.environ.get("JAVA_GATEWAY_URL")
    if explicit:
        return explicit.rstrip("/")
    # 与 WebClientConfig 中的默认值保持一致（python.service.base-url）。
    settings = get_settings()
    return "http://localhost:8080"


def _shared_secret() -> str:
    return os.environ.get("INTERNAL_SHARED_SECRET") or _DEFAULT_SHARED_SECRET


async def write_error_book_node(state: AgentState) -> AgentState:
    """把 corrections POST 到 Java /api/internal/error-book/batch。失败时旁路。"""
    t0 = time.time()
    corrections: list[Correction] = list(state.get("corrections") or [])
    if not corrections:
        # 没有 correction 时直接透传 state + 写一条零入参的审计。
        await write_audit(
            "write_error_book",
            str(state.get("user_id", "")),
            str(state.get("session_id", "")),
            int(state.get("turn_id", 0)),
            input_data={"corrections": []},
            output_data={"inserted": 0, "skipped": "empty"},
            latency_ms=int((time.time() - t0) * 1000),
            model_name="http",
            prompt_version=None,
        )
        return state

    user_id = str(state.get("user_id", "") or "")
    if not user_id:
        logger.warning("write_error_book: missing user_id; skipping")
        return state

    payload: list[dict[str, Any]] = []
    for c in corrections:
        if not isinstance(c, dict):
            continue
        original = (c.get("original") or "").strip()
        corrected = (c.get("corrected") or "").strip()
        if not original or not corrected:
            continue
        payload.append({
            "type": c.get("type") or "grammar",
            "original": original,
            "corrected": corrected,
            "explanation": c.get("explanation") or "",
        })

    if not payload:
        return state

    body = {
        "sessionId": state.get("session_id", ""),
        "turnId": int(state.get("turn_id", 0) or 0),
        "corrections": payload,
    }
    headers = {
        "X-Internal-Secret": _shared_secret(),
        "X-User-Id": user_id,
        "Content-Type": "application/json",
    }
    url = _java_base_url() + "/api/internal/error-book/batch"

    inserted = 0
    error: str | None = None
    try:
        # 短超时：错题本是写路径，必须不阻塞主链。失败就旁路。
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=body, headers=headers)
            if resp.status_code >= 400:
                error = f"http_{resp.status_code}: {resp.text[:200]}"
            else:
                try:
                    data = resp.json()
                    if isinstance(data, dict) and isinstance(data.get("data"), dict):
                        inserted = int(data["data"].get("inserted", 0) or 0)
                    elif isinstance(data, dict) and "inserted" in data:
                        inserted = int(data.get("inserted", 0) or 0)
                except Exception as parse_exc:  # noqa: BLE001
                    error = f"parse_failed: {parse_exc}"
    except Exception as exc:  # noqa: BLE001
        # 任何网络 / 序列化错误 —— 记 warning，不阻断主链。
        error = str(exc)
        logger.warning(
            "write_error_book: Java call failed (user=%s session=%s turn=%s): %s",
            user_id, state.get("session_id", ""), state.get("turn_id", 0), error,
        )

    latency_ms = int((time.time() - t0) * 1000)
    logger.info(
        "[NODE] write_error_book user=%s session=%s turn=%s corrections=%d inserted=%d latency=%dms%s",
        user_id, state.get("session_id", ""), state.get("turn_id", 0),
        len(payload), inserted, latency_ms,
        f" error={error}" if error else "",
    )

    await write_audit(
        "write_error_book",
        user_id,
        str(state.get("session_id", "")),
        int(state.get("turn_id", 0)),
        input_data={"corrections_count": len(payload)},
        output_data={"inserted": inserted, "error": error},
        latency_ms=latency_ms,
        model_name="http",
        prompt_version=None,
    )

    # 在 extra 中留下节点产物的痕迹，方便后续节点 / 调试查看。
    extra = dict(state.get("extra") or {})
    extra["error_book_inserted"] = inserted
    if error:
        extra["error_book_error"] = error
    return {**state, "extra": extra}
