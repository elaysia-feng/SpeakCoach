"""LLM 工厂 —— 根据设置返回配置好的 chat 模型。

支持三种 provider：
  - openai    : langchain_openai.ChatOpenAI（真正的 OpenAI 或任何 OpenAI 兼容端点）
  - anthropic : langchain_anthropic.ChatAnthropic
  - minimax   : langchain_anthropic.ChatAnthropic，指向 MiniMax 的网关

如果配置的 provider 没有 API key，工厂会返回一个确定性的
`StubChatModel`，以便 workflow 的其余部分（以及测试套件）在没有真实
上游的情况下仍能端到端运行。
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage

from .config import Settings, get_settings

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 公共接口
# ---------------------------------------------------------------------------


def get_llm(*, settings: Optional[Settings] = None, json_mode: bool = False) -> BaseChatModel:
    """返回一个配置好的 chat 模型。若没有 API key，则回退到确定性 stub，
    以保证服务仍能启动。

    ``json_mode`` 参数保留仅为向后兼容（``workflow/nodes/common.chat_json``
    仍会传入）—— JSON 约束完全由 prompt 内的"只返回 JSON"指令 + 调用方
    ``extract_json`` tolerant 解析器处理，**不在 chat model 层强加
    ``response_format``**。AI-Resume-Forge 的做法亦如此。
    """
    del json_mode  # 显式忽略 —— 不要在 client 层塞 response_format
    cfg = settings or get_settings()
    provider = cfg.llm_provider

    if provider == "openai":
        return _build_openai(cfg)
    if provider == "anthropic":
        return _build_anthropic(cfg)
    if provider == "minimax":
        return _build_minimax(cfg)

    raise ValueError(f"Unknown LLM provider: {provider!r}")


# ---------------------------------------------------------------------------
# Provider 构造器
# ---------------------------------------------------------------------------


def _active_model(cfg: Settings, provider: str, fallback: str) -> str:
    """解析某个 provider 当前生效的模型名称。

    ``LLM_MODEL``（``cfg.llm_model``）是日常调用方设置的唯一环境变量 —
    只要它非空，就会对所有 provider 生效。若为空，则回退到各 provider
    的默认模型（``anthropic_model`` / ``minimax_model``，OpenAI 直接使用
    同一个 ``llm_model``）。
    """
    if cfg.llm_model:
        return cfg.llm_model
    return fallback


def _build_openai(cfg: Settings) -> BaseChatModel:
    model = _active_model(cfg, "openai", "gpt-4o-mini")
    if not cfg.openai_api_key:
        logger.warning("OPENAI_API_KEY not set — using StubChatModel")
        return StubChatModel(provider="openai", model=model)
    try:
        from langchain_openai import ChatOpenAI
    except ImportError:  # pragma: no cover - import guard
        logger.warning("langchain-openai not installed — using StubChatModel")
        return StubChatModel(provider="openai", model=model)
    kwargs: dict[str, Any] = {
        "model": model,
        "temperature": cfg.llm_temperature,
        "max_tokens": cfg.llm_max_tokens,
        "api_key": cfg.openai_api_key,
        "base_url": cfg.openai_base_url or None,
    }
    logger.info("LLM ready: provider=openai model=%s base_url=%s", model, cfg.openai_base_url)
    return ChatOpenAI(**kwargs)


def _build_anthropic(cfg: Settings) -> BaseChatModel:
    model = _active_model(cfg, "anthropic", cfg.anthropic_model)
    if not cfg.anthropic_api_key:
        logger.warning("ANTHROPIC_API_KEY not set — using StubChatModel")
        return StubChatModel(provider="anthropic", model=model)
    try:
        from langchain_anthropic import ChatAnthropic
    except ImportError:  # pragma: no cover - import guard
        logger.warning("langchain-anthropic not installed — using StubChatModel")
        return StubChatModel(provider="anthropic", model=model)
    kwargs: dict[str, Any] = {
        "model": model,
        "temperature": cfg.llm_temperature,
        "max_tokens": cfg.llm_max_tokens,
        "api_key": cfg.anthropic_api_key,
        "base_url": cfg.anthropic_base_url or None,
    }
    logger.info("LLM ready: provider=anthropic model=%s base_url=%s", model, cfg.anthropic_base_url)
    return ChatAnthropic(**kwargs)


def _build_minimax(cfg: Settings) -> BaseChatModel:
    """MiniMax 走 OpenAI 兼容端点 —— 直接用 ChatOpenAI。

    不要用 langchain_anthropic：MiniMax 官方给的是 OpenAI 协议网关，
    Anthropic 协议的 ``extra`` kwarg 会被 MiniMax 网关拒绝（实测
    ``AsyncMessages.create() got an unexpected keyword argument 'extra'``）。
    结构化输出由 prompt 约束 + 调用方 tolerant JSON 解析器处理。
    """
    model = _active_model(cfg, "minimax", cfg.minimax_model)
    if not cfg.minimax_api_key:
        logger.warning("MINIMAX_API_KEY not set — using StubChatModel")
        return StubChatModel(provider="minimax", model=model)
    try:
        from langchain_openai import ChatOpenAI
    except ImportError:  # pragma: no cover - import guard
        logger.warning("langchain-openai not installed — using StubChatModel")
        return StubChatModel(provider="minimax", model=model)
    kwargs: dict[str, Any] = {
        "model": model,
        "temperature": cfg.llm_temperature,
        "max_tokens": cfg.llm_max_tokens,
        "api_key": cfg.minimax_api_key,
        "base_url": cfg.minimax_base_url or None,
    }
    logger.info("LLM ready: provider=minimax model=%s base_url=%s", model, cfg.minimax_base_url)
    return ChatOpenAI(**kwargs)


# ---------------------------------------------------------------------------
# Stub（无 key）chat 模型 —— 用于测试和离线运行
# ---------------------------------------------------------------------------


class StubChatModel(BaseChatModel):
    """确定性的离线 chat 模型。对任何 prompt 都回显一个合理的 JSON 形式
    回复，从而保证下游解析不会崩溃。
    """

    provider: str = "stub"
    model: str = "stub-1"

    @property
    def _llm_type(self) -> str:  # pragma: no cover - LangChain API
        return "stub"

    def _generate(self, messages: list[BaseMessage], **_: Any) -> Any:
        # 懒加载 import，让文件顶部保持简洁。
        from langchain_core.outputs import ChatGeneration, ChatResult

        prompt_text = "\n".join(getattr(m, "content", "") for m in messages)
        text = self._stub_reply(prompt_text)
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=text))])

    async def _agenerate(self, messages: list[BaseMessage], **_: Any) -> Any:
        from langchain_core.outputs import ChatGeneration, ChatResult

        prompt_text = "\n".join(getattr(m, "content", "") for m in messages)
        text = self._stub_reply(prompt_text)
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=text))])

    @staticmethod
    def _stub_reply(prompt: str) -> str:
        p = prompt.lower()
        if "json" in p and ("{" in p or "[" in p):
            # 顺序很重要：把最具体的短语放在前面，避免被 prompt 中
            # 靠前出现的更宽泛关键字所掩盖。（例如 "session summary" 应该
            # 优先于单独的 "summary"，"ability score" 应该优先于 "score"。）
            if "correction" in p:
                return "[]"
            if "shadow" in p or "upgraded" in p:
                return '{"upgraded": "", "why": ""}'
            if "ability" in p and "score" in p:
                return '{"grammar": 70, "vocabulary": 70, "fluency": 70, "logic": 70}'
            if "strategy" in p:
                return '{"strategy": "normal_follow_up", "reason": "stub"}'
            if "session summary" in p or ("summary" in p and "session" in p):
                return (
                    '{"total_turns": 0, "error_counts_by_type": '
                    '{"grammar": 0, "vocab": 0, "fluency": 0, "logic": 0}, '
                    '"ability_score_delta": {"grammar": 0, "vocabulary": 0, "fluency": 0, "logic": 0}, '
                    '"next_focus": "Practice free conversation.", "highlights": []}'
                )
            if "summary" in p:
                return (
                    '{"total_turns": 0, "error_counts_by_type": '
                    '{"grammar": 0, "vocab": 0, "fluency": 0, "logic": 0}, '
                    '"ability_score_delta": {"grammar": 0, "vocabulary": 0, "fluency": 0, "logic": 0}, '
                    '"next_focus": "Practice free conversation.", "highlights": []}'
                )
            if "reply" in p or "question" in p:
                return '{"reply": "Could you tell me a bit more about that?"}'
            return "{}"
        return "Sure, please go on."
