"""内部批量端点 —— 接收 RunEventBatch 并暂时只记录日志。

Java 端以批量方式向这里 POST audit_log 行；我们接收后存放在内存中
（以便 smoke test 校验形状）。未来的迭代会通过 Java 的
/internal/audit 端点转发到 audit_log 表。
"""

from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import APIRouter

from ..models import RunEventBatch

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["internal-batch"])


# 内存环形缓冲区，供调用方验证事件已到达（供测试使用）。
_RECENT_BATCHES: list[dict[str, Any]] = []
_RECENT_MAX = 50


@router.post("/run-event-batch")
async def persist_run_event_batch(batch: RunEventBatch) -> dict[str, Any]:
    received_at = int(time.time() * 1000)
    payload = {
        "user_id": batch.user_id,
        "session_id": batch.session_id,
        "turn_id": int(batch.turn_id),
        "events": [event.model_dump() for event in batch.events],
        "received_at": received_at,
    }
    _RECENT_BATCHES.append(payload)
    if len(_RECENT_BATCHES) > _RECENT_MAX:
        del _RECENT_BATCHES[: len(_RECENT_BATCHES) - _RECENT_MAX]
    logger.info(
        "[AUDIT-BATCH] user=%s session=%s turn=%s events=%d",
        batch.user_id, batch.session_id, batch.turn_id, len(batch.events),
    )
    return {"accepted": len(batch.events), "received_at": received_at}


@router.get("/run-event-batch/recent")
async def recent_batches() -> list[dict[str, Any]]:
    """返回最近的批量（测试辅助方法）。"""
    return list(_RECENT_BATCHES)
