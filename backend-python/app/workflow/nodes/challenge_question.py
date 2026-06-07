"""challenge_question 节点。"""

from __future__ import annotations

from ..state import AgentState
from .followup import run_followup_node


async def challenge_node(state: AgentState) -> AgentState:
    return await run_followup_node(
        state,
        "challenge",
        'Challenge the user to use richer vocabulary/structure. Return JSON: {"reply":"..."}.',
        context={"SCENE": state.get("scene") or "free_talk"},
    )
