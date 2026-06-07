"""load_session_state 节点。"""

from __future__ import annotations

import logging
import time

from ..state import AgentState
from .common import now_ms

logger = logging.getLogger(__name__)


async def load_session_state_node(state: AgentState) -> AgentState:
    """加载每个会话的 state，并打上 turn 元数据。"""
    t0 = time.time()
    turn_id = int(state.get("turn_id", 0))
    logger.info(
        "[NODE] load_session_state user=%s session=%s turn=%s",
        state.get("user_id", ""), state.get("session_id", ""), turn_id,
    )
    return {
        **state,
        "turn_count": turn_id,
        "messages": state.get("messages", []) or [],
        "errors_this_session": state.get("errors_this_session", []) or [],
        "common_errors": state.get("common_errors", []) or [],
        "extra": {**(state.get("extra") or {}), "load_state_latency_ms": now_ms(t0)},
    }
