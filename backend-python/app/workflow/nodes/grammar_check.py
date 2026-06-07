"""grammar_check 节点。"""

from __future__ import annotations

import logging
import time

from ..prompts import render_prompt
from ..state import AgentState, Correction
from .common import chat_json, now_ms

logger = logging.getLogger(__name__)


async def grammar_check_node(state: AgentState) -> AgentState:
    """调用 LLM ——> correction 字典列表。"""
    t0 = time.time()
    user_text = (state.get("user_text") or "").strip()
    scene = state.get("scene") or "free_talk"
    persona = state.get("coach_persona")
    system = render_prompt(
        "grammar",
        {"SCENE": scene},
        "You are an English tutor. Return a JSON array of corrections. Each item: "
        '{"original":"...","corrected":"...","type":"grammar|vocab|fluency|logic","severity":"low|medium|high"}. '
        "If the sentence is fine, return []. Respond with JSON only.",
        scene=scene,
    )
    raw = await chat_json(system, user_text or "(empty input)", persona=persona)
    corrections: list[Correction] = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, dict) and "original" in item and "corrected" in item:
                corrections.append(
                    {
                        "original": str(item.get("original", "")),
                        "corrected": str(item.get("corrected", "")),
                        "type": item.get("type", "grammar") if item.get("type") in {"grammar", "vocab", "fluency", "logic"} else "grammar",
                        "severity": item.get("severity", "medium") if item.get("severity") in {"low", "medium", "high"} else "medium",
                        "explanation": str(item.get("explanation", "")),
                    }
                )
    if not corrections and user_text:
        corrections.append(fallback_fluency_correction(user_text))
    errors_session = list(state.get("errors_this_session") or [])
    errors_session.extend(corrections)

    logger.info(
        "[NODE] grammar_check user=%s session=%s turn=%s found=%d latency=%dms",
        state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), len(corrections), now_ms(t0),
    )
    return {**state, "corrections": corrections, "errors_this_session": errors_session}


def fallback_fluency_correction(user_text: str) -> Correction:
    """保证每个非空 turn 都有一个可展示的反馈项。"""
    corrected = user_text.strip()
    if corrected:
        corrected = corrected[0].upper() + corrected[1:]
    if corrected and corrected[-1] not in ".!?":
        corrected += "."
    return {
        "original": user_text,
        "corrected": corrected or user_text,
        "type": "fluency",
        "severity": "low",
        "explanation": "Make the sentence complete and easy to read aloud.",
    }
