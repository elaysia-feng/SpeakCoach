"""strategy_router 节点及 strategy 路由。"""

from __future__ import annotations

import json
import logging
import time

from ..prompts import render_prompt
from ..state import AgentState, Strategy
from .common import chat_json, now_ms

logger = logging.getLogger(__name__)


async def strategy_router_node(state: AgentState) -> AgentState:
    """根据分数和最近的错误选择后续策略。"""
    t0 = time.time()
    score = state.get("ability_score") or {}
    grammar = int(score.get("grammar", 0))
    fluency = int(score.get("fluency", 0))
    vocab = int(score.get("vocabulary", 0))
    logic = int(score.get("logic", 0))
    corrections = state.get("corrections") or []
    error_history = state.get("errors_this_session") or []
    # M1-A: 来自长期画像的 common_errors 注入到候选范围。
    #     load_user_profile 节点已把画像里的 common_errors 写入
    #     state['common_errors_from_profile']。当本轮没有 high severity 错
    #     误、但历史画像里有反复出现的错误时，优先走 review_old_error 分支。
    profile_common_errors = state.get("common_errors_from_profile") or []
    turn_count = int(state.get("turn_count", 0))
    scene = state.get("scene") or "free_talk"
    persona = state.get("coach_persona")

    strategy: Strategy
    if corrections and any(c.get("severity") == "high" for c in corrections):
        strategy = "review_old_error"
    elif grammar < 50 or fluency < 50:
        strategy = "hint_question"
    elif grammar > 80 and fluency > 80 and turn_count >= 3:
        strategy = "challenge_question"
    else:
        strategy = "normal_follow_up"

    corrections_str = json.dumps(corrections, ensure_ascii=False)[:1500] if corrections else "[]"
    error_history_str = json.dumps(error_history, ensure_ascii=False)[:2000] if error_history else "[]"
    profile_errors_str = (
        json.dumps(profile_common_errors, ensure_ascii=False)[:1500]
        if profile_common_errors else "[]"
    )
    system = render_prompt(
        "strategy",
        {
            "SCENE": scene,
            "TURN_COUNT": str(turn_count),
            "GRAMMAR": str(grammar),
            "FLUENCY": str(fluency),
            "VOCAB": str(vocab),
            "LOGIC": str(logic),
            "CORRECTIONS": corrections_str,
            "ERROR_HISTORY": error_history_str,
            # M1-A: 注入长期画像里的 common_errors，让 LLM 在没有 high severity
            #     correction 时也能把它们纳入 review_old_error 的候选范围。
            "PROFILE_COMMON_ERRORS": profile_errors_str,
        },
        'Pick one of: hint_question | normal_follow_up | challenge_question | review_old_error. '
        'Return JSON: {"strategy":"<one>","reason":"..."}.',
        scene=scene,
    )
    user_prompt = "Apply the decision rules above and pick the single best follow-up strategy."
    raw = await chat_json(system, user_prompt, persona=persona)
    if isinstance(raw, dict):
        selected = str(raw.get("strategy", "")).strip()
        if selected in {"hint_question", "normal_follow_up", "challenge_question", "review_old_error"}:
            strategy = selected  # type: ignore[assignment]

    # M1-A: 启发式补丁 —— 在评分判断进入 normal_follow_up / hint_question 分支时，
    #     如果长期画像里仍有反复错误，优先走 review_old_error。
    #     这里的判断独立于 LLM 选择，确保历史 common_errors 总能被策略覆盖。
    if (
        strategy in {"normal_follow_up", "hint_question"}
        and profile_common_errors
        and not (corrections and any(c.get("severity") == "high" for c in corrections))
    ):
        strategy = "review_old_error"
        logger.info(
            "[NODE] strategy_router overrode to review_old_error due to %d profile common_errors",
            len(profile_common_errors),
        )

    logger.info(
        "[NODE] strategy_router user=%s session=%s turn=%s strategy=%s latency=%dms",
        state.get("user_id", ""), state.get("session_id", ""),
        state.get("turn_id", 0), strategy, now_ms(t0),
    )
    return {**state, "strategy": strategy}


def route_strategy(state: AgentState) -> str:
    strategy = state.get("strategy", "normal_follow_up")
    return {
        "hint_question": "hint_question",
        "normal_follow_up": "normal_follow_up",
        "challenge_question": "challenge_question",
        "review_old_error": "review_old_error",
    }.get(strategy, "normal_follow_up")
