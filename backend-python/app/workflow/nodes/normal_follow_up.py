"""normal_follow_up 节点。"""

from __future__ import annotations

from ..state import AgentState
from .followup import run_followup_node


async def normal_node(state: AgentState) -> AgentState:
    return await run_followup_node(
        state,
        "normal",
        'Ask a natural follow-up question. Return JSON: {"reply":"..."}.',
        context={"SCENE": state.get("scene") or "free_talk"},
    )
