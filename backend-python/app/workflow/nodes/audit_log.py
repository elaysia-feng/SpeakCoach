"""audit_log 节点。"""

from __future__ import annotations

from ...audit import write_audit
from ..prompts import PROMPT_VERSION
from ..state import AgentState


async def audit_log_node(state: AgentState) -> AgentState:
    """为本次 graph turn 写一条粗粒度的 Python 侧审计事件。"""
    extra = state.get("extra") or {}
    await write_audit(
        "langgraph_turn",
        str(state.get("user_id", "")),
        str(state.get("session_id", "")),
        int(state.get("turn_id", 0)),
        input_data={
            "scene": state.get("scene", ""),
            "user_text": state.get("user_text", ""),
        },
        output_data={
            "ai_reply": state.get("ai_reply", ""),
            "audio_url": state.get("audio_url", ""),
            "corrections": state.get("corrections", []),
            "shadow_answer": state.get("shadow_answer", ""),
            "ability_score": state.get("ability_score", {}),
            "pronunciation_score": state.get("pronunciation_score", 0),
            "strategy": state.get("strategy", "summary" if state.get("should_finish") else "normal_follow_up"),
            "summary": state.get("summary", {}),
            # M1-A: 把 load_user_profile 拉到的长期画像进审计(便于排查"为什么 strategy_router 这么选")。
            "loaded_profile": extra.get("loaded_profile"),
            "common_errors_from_profile": state.get("common_errors_from_profile", []),
            # M1-A: write_error_book 写入条数 + 失败原因(若有)。
            "error_book_inserted": extra.get("error_book_inserted"),
            "error_book_error": extra.get("error_book_error"),
        },
        model_name="langgraph",
        # TODO: bump in lockstep with `app/workflow/prompts.py::PROMPT_VERSION`
        prompt_version=PROMPT_VERSION,
    )
    return state
