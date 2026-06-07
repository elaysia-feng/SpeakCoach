"""暴露给 Java 网关的内部端点。

- POST /internal/turn                    —— 运行一次 LangGraph turn
- GET  /internal/health                  —— 服务存活 / 模型标识
- GET  /internal/turn/{thread_id}/history —— 回放该 thread 的最近 turn
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from fastapi import APIRouter, HTTPException

from ..models import (
    AudioTurnRequest,
    TurnHistoryItem,
    TurnHistoryResponse,
    TurnRequest,
    TurnResponse,
)
from ..speech_analysis import analyze_audio
from ..workflow import get_graph

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["internal"])


@router.get("/health")
async def health() -> dict[str, Any]:
    return {"status": "ok"}


@router.post("/turn", response_model=TurnResponse)
async def internal_turn(req: TurnRequest) -> TurnResponse:
    """为一个 (user, session) 对运行一次 LangGraph turn。"""
    return await run_turn(
        user_id=req.user_id,
        session_id=req.session_id,
        turn_id=req.turn_id,
        scene=req.scene,
        user_text=req.user_text or "",
        coach_persona=req.coach_persona,
        preferred_voice=req.preferred_voice,
    )


@router.post("/audio-turn", response_model=TurnResponse)
async def internal_audio_turn(req: AudioTurnRequest) -> TurnResponse:
    """先处理用户录音，再把转写文本交给原 LangGraph turn。"""
    analysis = await asyncio.to_thread(
        analyze_audio,
        req.audio_path,
        req.client_transcript,
        req.practice_mode,
        req.practice_target,
    )
    return await run_turn(
        user_id=req.user_id,
        session_id=req.session_id,
        turn_id=req.turn_id,
        scene=req.scene,
        user_text=analysis.transcript,
        user_audio_url=req.user_audio_url,
        speech_metrics=analysis.speech_metrics,
        word_timestamps=analysis.word_timestamps,
        coach_persona=req.coach_persona,
        preferred_voice=req.preferred_voice,
    )


async def run_turn(
    *,
    user_id: str,
    session_id: str,
    turn_id: int,
    scene: str,
    user_text: str,
    user_audio_url: str = "",
    speech_metrics: dict[str, Any] | None = None,
    word_timestamps: list[dict[str, Any]] | None = None,
    coach_persona: str = "warm_strict",
    preferred_voice: str = "linqian_voice",
) -> TurnResponse:
    """运行一次 LangGraph turn，并标准化 Java 侧消费的响应。"""
    thread_id = f"thread:{user_id}:{session_id}"
    logger.info(
        "[TURN] user=%s session=%s turn=%s thread=%s scene=%s persona=%s voice=%s",
        user_id, session_id, turn_id, thread_id, scene, coach_persona, preferred_voice,
    )

    initial: dict[str, Any] = {
        "user_id": user_id,
        "session_id": session_id,
        "turn_id": int(turn_id),
        "thread_id": thread_id,
        "scene": scene,
        "coach_persona": coach_persona or "warm_strict",
        "preferred_voice": preferred_voice or "linqian_voice",
        "user_text": user_text or "",
        "user_audio_url": user_audio_url,
        "speech_metrics": speech_metrics or {},
        "word_timestamps": word_timestamps or [],
        "messages": [],
    }

    graph = get_graph()
    config: dict[str, Any] = {"configurable": {"thread_id": thread_id}}
    try:
        final = await graph.ainvoke(initial, config=config)
    except Exception as exc:  # noqa: BLE001
        logger.exception("Graph invocation failed: %s", exc)
        # 优雅降级 —— 永远不要让聊天 turn 返回 500。
        return TurnResponse(
            transcript=user_text or "",
            user_audio_url=user_audio_url,
            ai_reply="Sorry, the AI service is temporarily unavailable. Please try again.",
            audio_url="",
            corrections="",
            corrections_list=[],
            shadow_answer="",
            strategy="normal_follow_up",
            speech_metrics=speech_metrics or {},
            word_timestamps=word_timestamps or [],
        )

    corrections = final.get("corrections", []) or []
    corrections_json = json.dumps(corrections, ensure_ascii=False) if corrections else ""

    return TurnResponse(
        transcript=final.get("user_text", "") or user_text or "",
        user_audio_url=final.get("user_audio_url", "") or user_audio_url,
        ai_reply=final.get("ai_reply", "") or "",
        audio_url=final.get("audio_url", "") or "",
        corrections=corrections_json,
        corrections_list=corrections,
        shadow_answer=final.get("shadow_answer", "") or "",
        ability_score=final.get("ability_score"),
        pronunciation_score=int(final.get("pronunciation_score", 0) or 0),
        strategy=final.get("strategy", "normal_follow_up"),
        summary=final.get("summary"),
        speech_metrics=final.get("speech_metrics", {}) or {},
        word_timestamps=final.get("word_timestamps", []) or [],
    )


@router.get("/turn/{thread_id}/history", response_model=TurnHistoryResponse)
async def turn_history(thread_id: str) -> TurnHistoryResponse:
    """返回某个 LangGraph thread 可回放的 turn 历史。

    读取持久化 checkpointer 的 state history。若 checkpointer 是内存版
    且进程已重启，则历史会为空。
    """
    graph = get_graph()
    config: dict[str, Any] = {"configurable": {"thread_id": thread_id}}
    history: list[TurnHistoryItem] = []
    try:
        state_history = graph.get_state_history(config)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to read state history for %s: %s", thread_id, exc)
        raise HTTPException(status_code=503, detail="history_unavailable") from exc

    for snap in list(state_history)[:50]:
        values = getattr(snap, "values", {}) or {}
        if not values:
            continue
        history.append(
            TurnHistoryItem(
                user_text=values.get("user_text", ""),
                user_audio_url=values.get("user_audio_url", ""),
                ai_reply=values.get("ai_reply", ""),
                audio_url=values.get("audio_url", ""),
                corrections=json.dumps(values.get("corrections", []), ensure_ascii=False),
                shadow_answer=values.get("shadow_answer", ""),
                strategy=values.get("strategy", "normal_follow_up"),
                turn_id=int(values.get("turn_id", 0)),
            )
        )

    return TurnHistoryResponse(thread_id=thread_id, history=history)
