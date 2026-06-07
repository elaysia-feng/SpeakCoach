"""LangGraph node 文件共用的辅助函数。"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from langchain_core.messages import HumanMessage, SystemMessage

from ...llm_factory import get_llm
from ..parsers import extract_json
from ..prompts import system_role_for
from ..state import Correction

logger = logging.getLogger(__name__)


async def chat_json(system_prompt: str, user_prompt: str, persona: str | None = None) -> Any:
    """执行一次期望返回 JSON 的 LLM 调用；返回解析后的 JSON，若失败则返回 None。

    M1-B: 当传入 `persona` 时,把对应的 system_role 前缀拼接到 system 消息顶部,
    让人设/语气影响 LLM 的所有调用,而不必改每个 prompt 文件本身。
    """
    llm = get_llm(json_mode=True)
    full_system = compose_system(system_prompt, persona)
    messages = [SystemMessage(content=full_system), HumanMessage(content=user_prompt)]
    try:
        resp = await asyncio.wait_for(llm.ainvoke(messages), timeout=45)
    except Exception as exc:  # noqa: BLE001
        logger.warning("LLM call failed: %s", exc)
        return None
    parsed = extract_json(getattr(resp, "content", "") or "")
    if parsed is not None:
        return parsed

    repair_prompt = (
        "Your previous answer was not valid JSON. Return ONLY the JSON value required by "
        "the system prompt. Do not include prose, markdown, code fences, or explanations."
    )
    try:
        repaired = await asyncio.wait_for(
            llm.ainvoke([*messages, HumanMessage(content=repair_prompt)]),
            timeout=20,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("LLM JSON repair call failed: %s", exc)
        return None
    return extract_json(getattr(repaired, "content", "") or "")


async def chat_text(system_prompt: str, user_prompt: str, persona: str | None = None) -> str:
    """执行一次期望返回自由文本的 LLM 调用；返回原始文本，失败时返回空串。

    M1-B: 与 chat_json 共享 persona 前缀注入逻辑。
    """
    llm = get_llm(json_mode=False)
    full_system = compose_system(system_prompt, persona)
    try:
        resp = await asyncio.wait_for(
            llm.ainvoke([SystemMessage(content=full_system), HumanMessage(content=user_prompt)]),
            timeout=45,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("LLM call failed: %s", exc)
        return ""
    return (getattr(resp, "content", "") or "").strip()


def compose_system(base_prompt: str, persona: str | None) -> str:
    """把 persona 的 system_role 前缀拼到原始 system prompt 顶部。

    用 `[[SYSTEM_ROLE:<persona>]]` 哨兵标记检测/清除"已经注入过",避免
    多次调用导致多个 persona 同时出现。比子串匹配更稳,即使 role 文本
    意外出现在 base 里也不会误判。

    行为:
    - persona 为空 → 原样返回
    - 同一 persona 已注入 → 原样返回(幂等)
    - 切换 persona → 先清掉旧 sentinel,再加新的(避免 LLM 看到冲突)
    """
    if not persona:
        return base_prompt
    role = system_role_for(persona)
    if not role:
        return base_prompt
    sentinel = f"[[SYSTEM_ROLE:{persona}]]"
    if sentinel in base_prompt:
        return base_prompt
    # 清掉旧的任意 [[SYSTEM_ROLE:...]] 标记,保证只有一个 persona 在生效
    import re
    cleaned = re.sub(r"\[\[SYSTEM_ROLE:[^\]]+\]\]\n?", "", base_prompt).lstrip("\n")
    return f"{sentinel}\n{role}\n\n{cleaned}"


def now_ms(t0: float) -> int:
    return int((time.time() - t0) * 1000)


def text_pronunciation_score(user_text: str, corrections: list[Correction]) -> int:
    """仅基于文本的发音代理分数（待浏览器发送音频/ASR 置信度后切换为真实打分）。"""
    text = (user_text or "").strip()
    if not text:
        return 0
    score = 78
    word_count = len(text.split())
    if word_count <= 2:
        score -= 10
    if any(c.get("type") == "fluency" for c in corrections):
        score -= 12
    if any(c.get("severity") == "high" for c in corrections):
        score -= 8
    return max(0, min(100, score))
