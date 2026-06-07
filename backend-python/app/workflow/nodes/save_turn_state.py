"""save_turn_state 节点。"""

from __future__ import annotations

from langchain_core.messages import AIMessage, HumanMessage

from ..state import AgentState


async def save_turn_state_node(state: AgentState) -> AgentState:
    """通过 LangGraph checkpointer 持久化可回放的 turn state。"""
    messages = []
    user_text = state.get("user_text", "")
    ai_reply = state.get("ai_reply", "")
    if user_text:
        messages.append(HumanMessage(content=user_text))
    if ai_reply:
        messages.append(AIMessage(content=ai_reply))
    return {
        "messages": messages,
        "extra": {
            **(state.get("extra") or {}),
            "saved_turn": {
                "turn_id": state.get("turn_id", 0),
                "strategy": state.get("strategy", "summary" if state.get("should_finish") else "normal_follow_up"),
                "audio_url": state.get("audio_url", ""),
            },
        },
    }
