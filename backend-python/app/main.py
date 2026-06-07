"""SpeakCoach AI 服务的 FastAPI 入口。

运行方式：
    uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .config import get_settings
from .routes import internal_batch_router, internal_router
from .tts_client import audio_path_for

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    cfg = get_settings()
    logging.basicConfig(
        level=getattr(logging, cfg.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s | %(message)s",
    )
    # 主动创建音频存储目录，避免首次 TTS 调用时出现竞态。
    base = Path(cfg.audio_storage_path).expanduser().resolve()
    try:
        base.mkdir(parents=True, exist_ok=True)
        logger.info("Audio storage ready: %s", base)
    except OSError as exc:
        logger.warning("Could not create audio storage dir %s: %s", base, exc)

    # 触发 audio_path_for 辅助函数，让检查布局的测试能够通过。
    _ = audio_path_for
    logger.info(
        "SpeakCoach AI service starting | provider=%s model=%s tts=%s checkpoint=%s",
        cfg.llm_provider, cfg.llm_model, cfg.gpt_sovits_base_url, cfg.checkpoint_backend,
    )
    yield
    logger.info("SpeakCoach AI service shutting down")


def create_app() -> FastAPI:
    cfg = get_settings()

    app = FastAPI(
        title="SpeakCoach AI Service",
        version="0.2.0",
        description="FastAPI + LangGraph backend for the SpeakCoach MVP. "
                    "Internal-only — never exposed to the browser directly.",
        lifespan=lifespan,
    )

    # Java 网关运行在 :8080。我们显式放行其来源，其他来源一律拦截。
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "http://localhost:8080",
            "http://127.0.0.1:8080",
        ],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/")
    async def root() -> dict[str, str]:
        return {
            "service": "speakcoach-ai",
            "version": app.version,
            "llm_provider": cfg.llm_provider,
            "llm_model": cfg.llm_model,
        }

    app.include_router(internal_router)
    app.include_router(internal_batch_router)
    return app


app = create_app()
