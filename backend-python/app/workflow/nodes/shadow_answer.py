"""shadow_answer 节点。"""

from __future__ import annotations

import json
import logging
import re
import time

from ..prompts import render_prompt
from ..state import AgentState, Correction
from .common import chat_json, now_ms

logger = logging.getLogger(__name__)


async def shadow_answer_node(state: AgentState) -> AgentState:
    """调用 LLM ——> 用户句子的升级版 / 地道表达版本。"""
    t0 = time.time()
    user_text = state.get("user_text") or ""
    corrections = state.get("corrections") or []
    corrections_str = json.dumps(corrections, ensure_ascii=False)[:2000] if corrections else "[]"
    scene = state.get("scene") or "free_talk"
    persona = state.get("coach_persona")
    system = render_prompt(
        "shadow",
        {
            "SCENE": scene,
            "USER_TEXT": user_text or "(empty)",
            "CORRECTIONS": corrections_str,
        },
        'Upgrade the sentence. Return JSON: {"upgraded":"...","why":"..."}. Respond with JSON only.',
        scene=scene,
    )
    user_prompt = f"User said: {user_text}\nCorrections: {corrections_str}\nReturn the upgraded version."
    raw = await chat_json(system, user_prompt, persona=persona)
    upgraded = ""
    if isinstance(raw, dict):
        upgraded = str(raw.get("upgraded", "")).strip() or user_text
    else:
        upgraded = user_text
    if not corrections:
        inferred = inferred_shadow_correction(user_text, upgraded)
        if inferred:
            corrections = [inferred]
            errors_session = list(state.get("errors_this_session") or [])
            errors_session.append(inferred)
            state = {**state, "corrections": corrections, "errors_this_session": errors_session}
    logger.info(
        "[NODE] shadow_answer user=%s session=%s turn=%s latency=%dms",
        state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), now_ms(t0),
    )
    return {**state, "shadow_answer": upgraded}


def inferred_shadow_correction(original: str, upgraded: str) -> Correction | None:
    """当 grammar 节点过于保守时，用 shadow answer 生成一条展示用纠错。"""
    original = (original or "").strip()
    upgraded = (upgraded or "").strip()
    if not original or not upgraded:
        return None
    if normalize_text(original) == normalize_text(upgraded):
        return None
    return {
        "original": original,
        "corrected": upgraded,
        "type": "fluency",
        "severity": "medium",
        "explanation": "The original sentence sounds unnatural; this version expresses the idea more clearly.",
    }


def normalize_text(text: str) -> str:
    return " ".join(re.findall(r"[a-z0-9']+", text.lower()))
