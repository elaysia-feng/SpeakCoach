"""SpeakCoach workflow 的 LangGraph StateGraph 构建器。

拓扑结构（与设计文档中的流程图保持一致）：

    START -> load_state -> load_user_profile -> grammar_check -> shadow_answer
          -> ability_analyze -> should_finish
          --(finish)--> generate_summary -> save_report -> tts_generate
          --(continue)--> strategy_router -> branch
                        -> generate_reply -> tts_generate
          -> tts_generate -> save_turn_state -> write_error_book -> audit_log -> END
"""

from __future__ import annotations

import logging
from functools import lru_cache
from typing import Any

from langgraph.graph import END, START, StateGraph

from ..checkpoint import build_checkpointer
from . import nodes as n
from .state import AgentState

logger = logging.getLogger(__name__)


def build_graph() -> Any:
    """编译并返回可运行的 LangGraph 对象。"""
    checkpointer = build_checkpointer()

    graph = StateGraph(AgentState)

    graph.add_node("load_state", n.load_session_state_node)
    # M1-A: 在 load_state 之后注入长期画像加载节点 —— 失败旁路。
    graph.add_node("load_user_profile", n.load_user_profile_node)
    graph.add_node("grammar_check", n.grammar_check_node)
    graph.add_node("shadow_answer", n.shadow_answer_node)
    graph.add_node("ability_analyze", n.ability_analyze_node)
    graph.add_node("strategy_router", n.strategy_router_node)

    graph.add_node("hint_question", n.hint_node)
    graph.add_node("normal_follow_up", n.normal_node)
    graph.add_node("challenge_question", n.challenge_node)
    graph.add_node("review_old_error", n.review_old_node)

    graph.add_node("generate_reply", n.generate_reply_node)
    graph.add_node("should_finish", n.should_finish_node)
    graph.add_node("tts_generate", n.tts_generate_node)
    graph.add_node("generate_summary", n.generate_summary_node)
    graph.add_node("save_report", n.save_report_node)
    graph.add_node("save_turn_state", n.save_turn_state_node)
    graph.add_node("write_error_book", n.write_error_book_node)
    graph.add_node("audit_log", n.audit_log_node)

    # 线性主干
    graph.add_edge(START, "load_state")
    graph.add_edge("load_state", "load_user_profile")
    graph.add_edge("load_user_profile", "grammar_check")
    graph.add_edge("grammar_check", "shadow_answer")
    graph.add_edge("shadow_answer", "ability_analyze")
    graph.add_edge("ability_analyze", "should_finish")

    graph.add_conditional_edges(
        "should_finish",
        n.route_finish,
        {"finish": "generate_summary", "continue": "strategy_router"},
    )

    # 策略扇出
    graph.add_conditional_edges(
        "strategy_router",
        n.route_strategy,
        {
            "hint_question": "hint_question",
            "normal_follow_up": "normal_follow_up",
            "challenge_question": "challenge_question",
            "review_old_error": "review_old_error",
        },
    )

    # 扇入到 reply
    for branch in ("hint_question", "normal_follow_up", "challenge_question", "review_old_error"):
        graph.add_edge(branch, "generate_reply")

    graph.add_edge("generate_reply", "tts_generate")
    graph.add_edge("generate_summary", "save_report")
    graph.add_edge("save_report", "tts_generate")
    graph.add_edge("tts_generate", "save_turn_state")
    graph.add_edge("save_turn_state", "write_error_book")
    graph.add_edge("write_error_book", "audit_log")
    graph.add_edge("audit_log", END)

    compiled = graph.compile(checkpointer=checkpointer)
    logger.info("LangGraph compiled (checkpointer=%s)", type(checkpointer).__name__)
    return compiled


@lru_cache(maxsize=1)
def get_graph() -> Any:
    """惰性单例 —— 首次使用时构建一次。"""
    return build_graph()


__all__ = ["build_graph", "get_graph"]
