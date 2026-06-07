"""review_old_error 节点。"""

from __future__ import annotations

import logging
import time

from ..prompts import render_prompt
from ..state import AgentState
from .common import chat_json, now_ms

logger = logging.getLogger(__name__)


async def review_old_node(state: AgentState) -> AgentState:
    """请用户重做上一次比较重要的错误。"""
    t0 = time.time()
    errors = state.get("errors_this_session") or []
    old_error = errors[-1] if errors else None
    old_text = str(old_error.get("original", "")) if old_error else state.get("user_text", "")
    old_correction = str(old_error.get("corrected", "")) if old_error else ""
    scene = state.get("scene") or "free_talk"
    persona = state.get("coach_persona")
    system = render_prompt(
        "review_old",
        {
            "SCENE": scene,
            "OLD_ERROR": old_text or "(unclear)",
            "OLD_CORRECTION": old_correction or "(unknown)",
            "USER_TEXT": state.get("user_text", ""),
        },
        'The user previously made an error. Ask them to retry correctly. Return JSON: {"reply":"..."}.',
        scene=scene,
    )
    raw = await chat_json(system, f"User just said: {state.get('user_text', '')}", persona=persona)
    reply = str(raw.get("reply", "")).strip() if isinstance(raw, dict) else ""
    if not reply:
        reply = "Could you try saying that again, more carefully this time?"

    logger.info(
        "[NODE] review_old_error user=%s session=%s turn=%s reply_len=%d latency=%dms",
        state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), len(reply), now_ms(t0),
    )
    return {**state, "ai_reply": reply, "last_ai_question": reply}
