"""generate_summary 节点。"""

from __future__ import annotations

import json
import logging
import time
from collections import Counter

from ..prompts import render_prompt
from ..state import AgentState, Summary
from .common import chat_json, now_ms

logger = logging.getLogger(__name__)


async def generate_summary_node(state: AgentState) -> AgentState:
    """调用 LLM ——> 会话总结 JSON 以及口头总结回复。"""
    t0 = time.time()
    corrections = state.get("errors_this_session") or []
    err_counts = {"grammar": 0, "vocab": 0, "fluency": 0, "logic": 0}
    for correction in corrections:
        kind = correction.get("type", "grammar")
        if kind in err_counts:
            err_counts[kind] += 1

    scene = state.get("scene") or "free_talk"
    persona = state.get("coach_persona")
    turn_count = int(state.get("turn_count", 0))
    ability_score = state.get("ability_score") or {}
    orig_counter = Counter(
        str(c.get("original", "")).strip()
        for c in corrections
        if str(c.get("original", "")).strip()
    )
    recurring = [text for text, count in orig_counter.items() if count >= 2][:5]

    system = render_prompt(
        "summary",
        {
            "SCENE": scene,
            "TURN_COUNT": str(turn_count),
            "ERROR_COUNTS": json.dumps(err_counts, ensure_ascii=False),
            "ABILITY_SCORE": json.dumps(ability_score, ensure_ascii=False),
            "COMMON_ERRORS": json.dumps(recurring, ensure_ascii=False) if recurring else "[]",
        },
        'Return JSON: {"total_turns":int,"error_counts_by_type":{...},'
        '"ability_score_delta":{...},"next_focus":str,"highlights":[str]}.',
        scene=scene,
    )
    user_prompt = "Generate the session summary."
    raw = await chat_json(system, user_prompt, persona=persona)
    summary: Summary = {
        "total_turns": int(state.get("turn_count", 0)),
        "error_counts_by_type": err_counts,
        "ability_score_delta": {"grammar": 0, "vocabulary": 0, "fluency": 0, "logic": 0, "pronunciation": 0},
        "next_focus": "",
        "highlights": [],
    }
    if isinstance(raw, dict):
        if "next_focus" in raw and isinstance(raw["next_focus"], str):
            summary["next_focus"] = raw["next_focus"]
        if "highlights" in raw and isinstance(raw["highlights"], list):
            summary["highlights"] = [str(x) for x in raw["highlights"]]
        if isinstance(raw.get("ability_score_delta"), dict):
            summary["ability_score_delta"] = raw["ability_score_delta"]
        if isinstance(raw.get("error_counts_by_type"), dict):
            summary["error_counts_by_type"] = raw["error_counts_by_type"]

    logger.info(
        "[NODE] generate_summary user=%s session=%s turn=%s latency=%dms",
        state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), now_ms(t0),
    )
    next_focus = summary.get("next_focus") or "Keep practicing with complete answers."
    ai_reply = f"Great work today. Here is your session summary: {next_focus}"
    return {**state, "summary": summary, "ai_reply": ai_reply, "should_finish": True}
