"""tts_generate 节点。"""

from __future__ import annotations

import asyncio
import logging
import time

from ...tts_client import synthesize
from ..state import AgentState
from .common import now_ms

logger = logging.getLogger(__name__)


async def tts_generate_node(state: AgentState) -> AgentState:
    t0 = time.time()
    user_id = state.get("user_id", "")
    session_id = state.get("session_id", "")
    turn_id = int(state.get("turn_id", 0))
    ai_reply = state.get("ai_reply", "") or ""
    if not ai_reply:
        return {**state, "audio_url": ""}

    audio_url = await asyncio.to_thread(synthesize, ai_reply, user_id, session_id, turn_id)
    logger.info(
        "[NODE] tts_generate user=%s session=%s turn=%s url=%s latency=%dms",
        user_id, session_id, turn_id, audio_url, now_ms(t0),
    )
    return {**state, "audio_url": audio_url or ""}
