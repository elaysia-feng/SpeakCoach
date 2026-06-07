"""load_user_profile 节点 — M1-A 长期记忆里程碑。

在 load_session_state 之后、grammar_check 之前执行。负责把用户的长期
能力画像从 Java 拉到 state 里，并把 common_errors 注入后续 strategy_router
的候选范围。

降级策略：
  * 网络/超时/非 2xx 响应都被 try/except 吞掉，state 不变。
  * 解析不到 common_errors 时退化为空列表。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import httpx

from ..state import AgentState

logger = logging.getLogger(__name__)

# 默认内网 Java base URL — 与 application.yml 中 python.service.base-url 共享。
# 显式参数优先于默认值，便于 e2e 测试中注入本地 stub。
_DEFAULT_JAVA_BASE = os.environ.get("JAVA_GATEWAY_URL", "http://localhost:8080")
_INTERNAL_TOKEN_ENV = "INTERNAL_API_TOKEN"

# 单次 GET 最多给 1.5s —— 主链路 P95 ≤ 6s 的预算里这点开销可以忽略，
# 但同时保证 Java 卡死时不会拖垮 /api/chat。
_REQUEST_TIMEOUT_SEC = 1.5


async def load_user_profile_node(state: AgentState) -> AgentState:
    """把 Java 端 user_ability_profile 拉入 state；失败时旁路。"""
    user_id_raw = state.get("user_id")
    if user_id_raw in (None, "", 0):
        # 内部调用：没有 userId 直接返回。
        logger.info(
            "[NODE] load_user_profile: no user_id, skipping (session=%s)",
            state.get("session_id", ""),
        )
        return state
    try:
        user_id = int(user_id_raw)
    except (TypeError, ValueError) as exc:
        # 非数字 user_id(常见于 e2e 测试用 "u1" / UUID 风格) → 静默跳过,但留 INFO
        # 日志方便排查。生产环境 user_id 必然是 int,不会走到这里。
        logger.info(
            "[NODE] load_user_profile: user_id=%r is not int (%s), skipping",
            user_id_raw, exc,
        )
        return state

    base_url = os.environ.get("JAVA_GATEWAY_URL", _DEFAULT_JAVA_BASE).rstrip("/")
    token = os.environ.get(_INTERNAL_TOKEN_ENV, "")
    headers = {"X-Internal-Token": token} if token else {}

    url = f"{base_url}/api/internal/profile/{user_id}"
    try:
        async with httpx.AsyncClient(timeout=_REQUEST_TIMEOUT_SEC) as client:
            resp = await client.get(url, headers=headers)
        if resp.status_code != 200:
            logger.warning(
                "[NODE] load_user_profile: java %s returned status=%s",
                url, resp.status_code,
            )
            return state
        payload: Any = resp.json()
    except (httpx.HTTPError, asyncio.TimeoutError, ValueError) as exc:
        logger.warning(
            "[NODE] load_user_profile: java request failed: %s", exc,
        )
        return state
    except Exception as exc:  # noqa: BLE001
        # 任何意外都不要拖垮主流程。
        logger.warning(
            "[NODE] load_user_profile: unexpected error: %s", exc,
        )
        return state

    if not isinstance(payload, dict) or not payload.get("exists", False):
        # 用户还没有画像 —— strategy_router 没有任何 history 可用，照常运行。
        return state

    common_errors = _extract_common_errors(payload)
    extra = dict(state.get("extra") or {})
    extra["loaded_profile"] = {
        "user_id": payload.get("userId"),
        "grammar_score": payload.get("grammarScore"),
        "vocabulary_score": payload.get("vocabularyScore"),
        "fluency_score": payload.get("fluencyScore"),
        "logic_score": payload.get("logicScore"),
        "cefr_level": payload.get("cefrLevel"),
    }
    logger.info(
        "[NODE] load_user_profile user=%s common_errors=%d",
        user_id, len(common_errors),
    )
    return {
        **state,
        "common_errors_from_profile": common_errors,
        "extra": extra,
    }


def _extract_common_errors(payload: dict[str, Any]) -> list[str]:
    """从 Java 返回的 JSON 负载里提取反复出现的错误列表。

    接受三种形态：
      * 直接 list
      * 形如 '["foo", "bar"]' 的 JSON 字符串
      * 字段 commonErrors / common_errors
    """
    candidates = [payload.get("commonErrors"), payload.get("common_errors")]
    for raw in candidates:
        if raw is None:
            continue
        if isinstance(raw, list):
            return [str(x) for x in raw if x]
        if isinstance(raw, str) and raw.strip():
            try:
                parsed = json.loads(raw)
                if isinstance(parsed, list):
                    return [str(x) for x in parsed if x]
            except json.JSONDecodeError:
                # 不是 JSON 数组 —— 视为 0 项
                continue
    return []
