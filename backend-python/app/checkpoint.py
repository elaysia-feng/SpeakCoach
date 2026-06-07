"""LangGraph checkpointer 工厂。

`build_checkpointer()` 返回一个 LangGraph 兼容的 `BaseCheckpointSaver`。
默认使用内存版 saver，以便在没有 Redis 时也能运行 workflow。
设置 `CHECKPOINT_BACKEND=redis` 切换到 `RedisSaver`（懒加载导入）。
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from .config import Settings, get_settings

logger = logging.getLogger(__name__)


def build_checkpointer(
    redis_url: Optional[str] = None,
    *,
    settings: Optional[Settings] = None,
) -> Any:
    """返回一个 LangGraph checkpointer。出错时回退到 MemorySaver。"""
    cfg = settings or get_settings()
    backend = cfg.checkpoint_backend
    url = redis_url or cfg.redis_url

    if backend == "redis" and url:
        try:
            from langgraph.checkpoint.redis import RedisSaver  # type: ignore

            saver = RedisSaver(url)
            try:
                saver.setup()  # create indexes on first use
            except Exception:  # noqa: BLE001
                logger.debug("RedisSaver.setup() no-op or already initialised")
            logger.info("Checkpointer: RedisSaver url=%s", url)
            return saver
        except Exception as exc:  # noqa: BLE001
            logger.warning("Failed to initialise RedisSaver (%s) — falling back to MemorySaver", exc)

    # 默认：内存版
    from langgraph.checkpoint.memory import MemorySaver  # 始终可用

    logger.info("Checkpointer: MemorySaver (in-process)")
    return MemorySaver()


__all__ = ["build_checkpointer"]
