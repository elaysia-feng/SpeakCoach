"""LLM 响应的 JSON 解析辅助工具。

LLM 经常会把 JSON 包在 markdown 代码块里、在 JSON 周围返回额外文字，
或者以多余的逗号结尾。本辅助函数会尝试多种策略来提取出合法的
JSON 对象 / 数组。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Optional

logger = logging.getLogger(__name__)


_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL)
_OBJECT_RE = re.compile(r"\{.*\}", re.DOTALL)
_ARRAY_RE = re.compile(r"\[.*\]", re.DOTALL)


def extract_json(text: str) -> Optional[Any]:
    """返回在 `text` 中找到的第一个可解析的 JSON 值，若无则返回 None。"""
    if not text:
        return None

    candidates: list[str] = []
    text = text.strip()

    # 1. 直接解析
    candidates.append(text)
    # 2. 剥离代码块围栏
    for m in _FENCE_RE.finditer(text):
        candidates.append(m.group(1).strip())
    # 3. 第一个 {...} 或 [...] 片段；若数组和对象同位置开始，优先保留数组整体。
    fragments: list[tuple[int, int, str]] = []
    obj = _OBJECT_RE.search(text)
    if obj:
        fragments.append((obj.start(), 1, obj.group(0)))
    arr = _ARRAY_RE.search(text)
    if arr:
        fragments.append((arr.start(), 0, arr.group(0)))
    candidates.extend(raw for _, _, raw in sorted(fragments))

    for raw in candidates:
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            # 最后一搏：尝试去掉末尾多余的逗号。
            try:
                return json.loads(re.sub(r",\s*([}\]])", r"\1", raw))
            except (ValueError, TypeError):
                continue

    logger.debug("No parseable JSON in LLM reply (length=%d)", len(text))
    return None


__all__ = ["extract_json"]
