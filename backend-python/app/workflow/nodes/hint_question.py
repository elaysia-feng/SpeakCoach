"""hint_question 节点。"""

from __future__ import annotations

from ..state import AgentState
from .followup import run_followup_node


async def hint_node(state: AgentState) -> AgentState:
    return await run_followup_node(
        state,
        "hint",
        'Give a one-sentence hint to nudge the user. Return JSON: {"reply":"..."}.',
        context={"SCENE": state.get("scene") or "free_talk"},
    )
