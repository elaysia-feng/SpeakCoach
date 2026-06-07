"""共用的 follow-up 节点执行器。"""

from __future__ import annotations

import json
import logging
import time

from ..prompts import render_prompt
from ..state import AgentState
from .common import chat_json, now_ms

logger = logging.getLogger(__name__)


async def run_followup_node(
    state: AgentState,
    prompt_name: str,
    default_system: str,
    context: dict | None = None,
) -> AgentState:
    t0 = time.time()
    user_text = state.get("user_text") or ""
    corrections = state.get("corrections") or []
    last_question = state.get("last_ai_question", "")
    scene = state.get("scene") or "free_talk"
    persona = state.get("coach_persona")
    ctx = dict(context or {})
    ctx.setdefault("SCENE", scene)
    system = render_prompt(prompt_name, ctx, default_system, scene=scene)
    user_prompt = (
        f"User just said: {user_text}\n"
        f"Recent corrections: {json.dumps(corrections, ensure_ascii=False)[:1500]}\n"
        f"Previous AI question (if any): {last_question}"
    )
    raw = await chat_json(system, user_prompt, persona=persona)
    reply = str(raw.get("reply", "")).strip() if isinstance(raw, dict) else ""
    if not reply:
        reply = "Could you say a little more about that?"

    logger.info(
        "[NODE] %s user=%s session=%s turn=%s reply_len=%d latency=%dms",
        prompt_name, state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), len(reply), now_ms(t0),
    )
    return {**state, "ai_reply": reply, "last_ai_question": reply}
