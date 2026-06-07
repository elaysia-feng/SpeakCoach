"""should_finish 节点及 finish 路由。"""

from __future__ import annotations

import logging
import time

from ..state import AgentState
from .common import now_ms

logger = logging.getLogger(__name__)


async def should_finish_node(state: AgentState) -> AgentState:
    """判断是否应该结束本次会话。"""
    t0 = time.time()
    user_text = (state.get("user_text") or "").lower()
    farewell_signals = ("bye", "goodbye", "结束", "拜拜", "see you", "that's all", "thanks bye")
    should_finish = any(sig in user_text for sig in farewell_signals) or int(state.get("turn_count", 0)) >= 50
    logger.info(
        "[NODE] should_finish user=%s session=%s turn=%s should_finish=%s latency=%dms",
        state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), should_finish, now_ms(t0),
    )
    return {**state, "should_finish": should_finish}


def route_finish(state: AgentState) -> str:
    return "finish" if state.get("should_finish") else "continue"
