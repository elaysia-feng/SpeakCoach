"""应用配置 —— 从环境变量 / .env 文件加载。

配置项可端到端配置，使同一份代码可以同时跑在 OpenAI、Anthropic，
或 MiniMax 兼容 Anthropic 的网关上。
"""

from __future__ import annotations

from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

LLMProvider = Literal["openai", "anthropic", "minimax"]
CheckpointBackend = Literal["memory", "redis"]


class Settings(BaseSettings):
    """AI 服务的集中式配置。

    所有值都从环境变量（或本地 `.env` 文件）读取。
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # --- LLM provider 选择 ---------------------------------------------------
    # 默认选择 ``minimax``（兼容 Anthropic），这样服务在没有 OpenAI key
    # 的情况下也能启动。若 ``minimax_api_key`` 为空，LLM 工厂会回退到
    # 确定性的 ``StubChatModel``。
    llm_provider: LLMProvider = "minimax"
    llm_model: str = "MiniMax-Text-01"
    llm_temperature: float = 0.4
    llm_max_tokens: int = 1024

    # --- OpenAI (provider=openai) --------------------------------------------
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"

    # --- Anthropic (provider=anthropic) --------------------------------------
    anthropic_api_key: str = ""
    anthropic_base_url: str = "https://api.anthropic.com"
    anthropic_model: str = "claude-3-5-sonnet-latest"

    # --- MiniMax (provider=minimax, 兼容 Anthropic) --------------------------
    minimax_api_key: str = ""
    minimax_base_url: str = "https://api.minimaxi.com"
    minimax_model: str = "MiniMax-Text-01"

    # --- GPT-SoVITS 本地 TTS --------------------------------------------------
    gpt_sovits_base_url: str = "http://localhost:9880"
    gpt_sovits_api_key: str = ""
    tts_ref_audio_path: str = ""
    tts_prompt_text: str = ""
    tts_prompt_language: str = "en"
    tts_speed: float = 1.0

    # --- 音频存储 ------------------------------------------------------------
    # 相对于项目根目录 —— 当本服务从 backend-python/ 启动时，会解析为
    # <项目根>/storage/audio。与 Java 后端默认值以及 .gitkeep 文件位置一致。
    audio_storage_path: str = "../storage/audio"
    audio_base_url: str = "/api/audio"
    tts_text_language: str = "en"

    # --- WhisperX / 语音分析 -------------------------------------------------
    whisperx_model: str = "base"
    whisperx_device: str = "cpu"
    whisperx_compute_type: str = "int8"
    whisperx_batch_size: int = 8

    # --- LangGraph checkpointing ---------------------------------------------
    checkpoint_backend: CheckpointBackend = "memory"
    redis_url: str = "redis://localhost:6379/0"

    # --- 服务 ----------------------------------------------------------------
    port: int = 9000
    log_level: str = "INFO"

    # 向后兼容别名（旧代码曾引用过这些名字）。
    @property
    def gpt_sovits_timeout(self) -> float:
        return 30.0


_settings: Settings | None = None


def get_settings() -> Settings:
    """惰性单例 —— 避免在模块导入时加载 .env。"""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings
