"""ability_analyze 节点。"""

from __future__ import annotations

import json
import logging
import time

from ..prompts import render_prompt
from ..state import AbilityScore, AgentState
from .common import chat_json, now_ms, text_pronunciation_score

logger = logging.getLogger(__name__)


async def ability_analyze_node(state: AgentState) -> AgentState:
    """调用 LLM ——> 语法/词汇/流利度/逻辑/发音分数。"""
    t0 = time.time()
    user_text = state.get("user_text") or ""
    corrections = state.get("corrections") or []
    speech_metrics = state.get("speech_metrics") or {}
    corrections_str = json.dumps(corrections, ensure_ascii=False)[:1500] if corrections else "[]"
    scene = state.get("scene") or "free_talk"
    persona = state.get("coach_persona")
    system = render_prompt(
        "ability",
        {
            "SCENE": scene,
            "USER_TEXT": user_text or "(empty)",
            "CORRECTIONS": corrections_str,
        },
        "Score 0-100 integers for grammar, vocabulary, fluency, logic. "
        'Return JSON: {"grammar":int,"vocabulary":int,"fluency":int,"logic":int}.',
        scene=scene,
    )
    user_prompt = (
        f"Score this turn.\nText: {user_text}\nCorrections: "
        f"{corrections_str}"
    )
    raw = await chat_json(system, user_prompt, persona=persona)

    pronunciation = metric_score(speech_metrics.get("pronunciation_score"))
    if pronunciation is None:
        pronunciation = text_pronunciation_score(user_text, corrections)
    score: AbilityScore = {
        "grammar": 0,
        "vocabulary": 0,
        "fluency": 0,
        "logic": 0,
        "pronunciation": pronunciation,
    }
    if isinstance(raw, dict):
        for key in ("grammar", "vocabulary", "fluency", "logic"):
            value = raw.get(key)
            if isinstance(value, (int, float)):
                score[key] = max(0, min(100, int(value)))
    fluency = metric_score(speech_metrics.get("fluency_score"))
    if fluency is not None:
        score["fluency"] = fluency
    logger.info(
        "[NODE] ability_analyze user=%s session=%s turn=%s score=%s latency=%dms",
        state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), score, now_ms(t0),
    )
    return {**state, "ability_score": score, "pronunciation_score": pronunciation}


def metric_score(value: object) -> int | None:
    if isinstance(value, (int, float)):
        return max(0, min(100, int(value)))
    return None
