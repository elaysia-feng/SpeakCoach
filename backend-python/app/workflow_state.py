"""一次 SpeakCoach turn 的 LangGraph state schema。

state 是流经图中每个节点的唯一真理来源。我们使用带 `total=False` 的
TypedDict，这样节点可以返回部分 state（只包含它想变更的键）。
"""

from __future__ import annotations

from typing import Annotated, Any, Literal, Optional, TypedDict

from langgraph.graph.message import add_messages


# ---------------------------------------------------------------------------
# 值类型
# ---------------------------------------------------------------------------


class Correction(TypedDict, total=False):
    original: str
    corrected: str
    type: Literal["grammar", "vocab", "fluency", "logic"]
    severity: Literal["low", "medium", "high"]
    explanation: str


class AbilityScore(TypedDict, total=False):
    grammar: int
    vocabulary: int
    fluency: int
    logic: int
    pronunciation: int


class Summary(TypedDict, total=False):
    total_turns: int
    error_counts_by_type: dict
    ability_score_delta: dict
    next_focus: str
    highlights: list


Strategy = Literal[
    "hint_question",
    "normal_follow_up",
    "challenge_question",
    "review_old_error",
]


# ---------------------------------------------------------------------------
# AgentState
# ---------------------------------------------------------------------------


class AgentState(TypedDict, total=False):
    # --- 身份 / 路由 --------------------------------------------------------
    user_id: str
    session_id: str
    turn_id: int
    thread_id: str
    scene: str
    # M1-B: 教练人格（warm_strict / friendly_tutor / ielts_examiner / patient_grandma）
    coach_persona: str
    # M1-B: TTS 音色标识
    preferred_voice: str

    # --- 对话 ---------------------------------------------------------------
    messages: Annotated[list, add_messages]
    user_text: str
    ai_reply: str
    corrections: list[Correction]
    shadow_answer: str
    ability_score: AbilityScore
    pronunciation_score: int
    speech_metrics: dict[str, Any]
    word_timestamps: list[dict[str, Any]]
    user_audio_url: str

    # --- 控制 ---------------------------------------------------------------
    strategy: Strategy
    should_finish: bool
    summary: Summary

    # --- tts ----------------------------------------------------------------
    audio_url: str

    # --- 跨 turn 记忆 -------------------------------------------------------
    turn_count: int
    last_ai_question: str
    errors_this_session: list[Correction]
    common_errors: list[str]
    # M1-A: 来自长期画像的反复错误（load_user_profile 节点写入）。
    #     与 session 内的 common_errors 区分开，保留来源标识。
    common_errors_from_profile: list[str]

    # --- 供节点共享草稿空间的自由字段 ----------------------------------------
    extra: dict[str, Any]


__all__ = [
    "AbilityScore",
    "AgentState",
    "Correction",
    "Strategy",
    "Summary",
]
