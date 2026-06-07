"""save_report 节点 — M1-A 长期记忆里程碑。

该节点是结束分支（should_finish → finish）的一部分，承担两件事：

  1. 把本轮 ability_score 写一条 audit —— 走 langgraph_turn 的 audit 端点。
  2. 把本轮能力评分 POST 到 Java 的 /api/internal/profile/snapshot，
     让 user_ability_history 表出现一条对应行。

所有写操作都在 try/except 里 —— 主链路绝不能因为快照写入失败而失败。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import httpx

from ...audit import write_audit
from ..prompts import PROMPT_VERSION
from ..state import AgentState

logger = logging.getLogger(__name__)

_DEFAULT_JAVA_BASE = os.environ.get("JAVA_GATEWAY_URL", "http://localhost:8080")
_INTERNAL_TOKEN_ENV = "INTERNAL_API_TOKEN"
_TIMEOUT_SEC = 2.0


async def save_report_node(state: AgentState) -> AgentState:
    """在会话结束分支上把当轮 ability_score 持久化为 user_ability_history 行。"""
    user_id_raw = state.get("user_id")
    score = state.get("ability_score") or {}

    # 1) audit —— 与 audit_log_node 同样的路径，但这里只写"save_report"自身的事件，
    #    避免和 audit_log_node 的 langgraph_turn 重复。
    try:
        await write_audit(
            "save_report",
            str(user_id_raw or ""),
            str(state.get("session_id", "")),
            int(state.get("turn_id", 0) or 0),
            input_data={"ability_score": score},
            output_data={"saved": True},
            model_name="langgraph",
            prompt_version=PROMPT_VERSION,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("save_report: write_audit failed: %s", exc)

    # 2) POST Java 内部端点，把快照写入 user_ability_history。
    if not user_id_raw or not score:
        return state

    try:
        user_id = int(user_id_raw)
    except (TypeError, ValueError):
        return state

    base_url = os.environ.get("JAVA_GATEWAY_URL", _DEFAULT_JAVA_BASE).rstrip("/")
    token = os.environ.get(_INTERNAL_TOKEN_ENV, "")
    headers = {"X-Internal-Token": token} if token else {}

    common_errors = state.get("common_errors") or state.get("common_errors_from_profile") or []
    if isinstance(common_errors, list):
        try:
            common_errors_str = json.dumps(common_errors, ensure_ascii=False)
        except (TypeError, ValueError):
            common_errors_str = "[]"
    elif isinstance(common_errors, str):
        common_errors_str = common_errors
    else:
        common_errors_str = "[]"

    body: dict[str, Any] = {
        "userId": user_id,
        "sessionId": state.get("session_id", ""),
        "turnId": int(state.get("turn_id", 0) or 0),
        "grammarScore": _maybe_int(score.get("grammar")),
        "vocabularyScore": _maybe_int(score.get("vocabulary")),
        "fluencyScore": _maybe_int(score.get("fluency")),
        "logicScore": _maybe_int(score.get("logic")),
        "commonErrors": common_errors_str,
    }

    url = f"{base_url}/api/internal/profile/snapshot"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_SEC) as client:
            resp = await client.post(url, json=body, headers=headers)
        if resp.status_code != 200:
            logger.warning(
                "save_report: java snapshot returned status=%s body=%s",
                resp.status_code, _truncate(resp.text),
            )
        else:
            logger.info(
                "[NODE] save_report user=%s session=%s turn=%s snapshot=ok",
                user_id, body["sessionId"], body["turnId"],
            )
    except (httpx.HTTPError, asyncio.TimeoutError) as exc:
        logger.warning("save_report: java snapshot failed: %s", exc)
    except Exception as exc:  # noqa: BLE001
        logger.warning("save_report: unexpected error: %s", exc)

    return state


def _maybe_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return max(0, min(100, int(value)))
    if isinstance(value, str):
        try:
            return max(0, min(100, int(value)))
        except ValueError:
            return None
    return None


def _truncate(s: str, limit: int = 200) -> str:
    if len(s) <= limit:
        return s
    return s[:limit] + "..."
