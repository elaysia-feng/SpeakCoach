"""暴露给 Java 网关的内部 FastAPI 路由。"""

from .internal import router as internal_router
from .internal_batch import router as internal_batch_router

__all__ = ["internal_router", "internal_batch_router"]
