"""Pydantic 模型 —— FastAPI 表面的请求 / 响应 / 事件结构。

这些结构与 Java `PythonClient` 消费方的契约保持一致。
"""

from __future__ import annotations

from typing import Any, Literal, Optional

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# 对话模型
# ---------------------------------------------------------------------------


class Correction(BaseModel):
    original: str
    corrected: str
    type: Literal["grammar", "vocab", "fluency", "logic"] = "grammar"
    severity: Literal["low", "medium", "high"] = "medium"
    explanation: Optional[str] = None


class AbilityScore(BaseModel):
    grammar: int = 0
    vocabulary: int = 0
    fluency: int = 0
    logic: int = 0
    pronunciation: int = 0


class TurnRequest(BaseModel):
    user_id: str
    session_id: str
    turn_id: int
    user_text: str
    scene: str = "free_talk"
    # M1-B: 教练人格（warm_strict / friendly_tutor / ielts_examiner / patient_grandma）。
    # 缺省时回退到 warm_strict；走 user.coach_persona 字段。
    coach_persona: str = "warm_strict"
    # M1-B: TTS 音色标识（当前仅 linqian_voice）。预留扩展。
    preferred_voice: str = "linqian_voice"


class AudioTurnRequest(BaseModel):
    user_id: str
    session_id: str
    turn_id: int
    audio_path: str
    user_audio_url: str = ""
    scene: str = "free_talk"
    client_transcript: str = ""
    practice_mode: str = ""
    practice_target: str = ""
    coach_persona: str = "warm_strict"
    preferred_voice: str = "linqian_voice"


class TurnResponse(BaseModel):
    transcript: str = ""
    user_audio_url: str = ""
    ai_reply: str = ""
    audio_url: str = ""
    corrections: str = ""           # 为 Java 代理使用的 JSON 编码字符串
    corrections_list: list[Correction] = Field(default_factory=list)
    shadow_answer: str = ""
    ability_score: Optional[AbilityScore] = None
    pronunciation_score: int = 0
    strategy: str = "normal_follow_up"
    summary: Optional[dict[str, Any]] = None
    speech_metrics: dict[str, Any] = Field(default_factory=dict)
    word_timestamps: list[dict[str, Any]] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# 历史 / 事件
# ---------------------------------------------------------------------------


class TurnHistoryItem(BaseModel):
    user_text: str
    user_audio_url: str = ""
    ai_reply: str
    audio_url: str = ""
    corrections: str = ""
    shadow_answer: str = ""
    strategy: str = "normal_follow_up"
    turn_id: int = 0
    timestamp: Optional[str] = None


class TurnHistoryResponse(BaseModel):
    thread_id: str
    history: list[TurnHistoryItem] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Run-event 批量（Java 将其持久化到 audit_log）
# ---------------------------------------------------------------------------


class RunEvent(BaseModel):
    node_name: str
    input: dict[str, Any] = Field(default_factory=dict)
    output: dict[str, Any] = Field(default_factory=dict)
    latency_ms: int = 0
    model_name: Optional[str] = None
    prompt_version: Optional[str] = None
    created_at: Optional[str] = None


class RunEventBatch(BaseModel):
    user_id: str
    session_id: str
    turn_id: int
    events: list[RunEvent] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# 总结端点
# ---------------------------------------------------------------------------


class SummaryResponse(BaseModel):
    user_id: str
    session_id: str
    turn_id: int
    summary: dict[str, Any] = Field(default_factory=dict)
    ability_score: Optional[AbilityScore] = None
    shadow_answer: str = ""


__all__ = [
    "AbilityScore",
    "AudioTurnRequest",
    "Correction",
    "RunEvent",
    "RunEventBatch",
    "SummaryResponse",
    "TurnHistoryItem",
    "TurnHistoryResponse",
    "TurnRequest",
    "TurnResponse",
]
