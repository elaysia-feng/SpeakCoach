"""generate_reply 节点。"""

from __future__ import annotations

from ..state import AgentState


async def generate_reply_node(state: AgentState) -> AgentState:
    """根据前面分支的输出组合最终的 ai_reply。"""
    ai_reply = state.get("ai_reply") or ""
    if not ai_reply:
        ai_reply = "Sorry, I missed that. Could you say it again?"
    return {**state, "ai_reply": ai_reply}
