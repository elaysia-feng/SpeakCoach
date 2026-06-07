"""M1-A (长期画像) + M1-B (persona + scene 子目录) 的覆盖测试。

全部走进程内调用,不依赖 Java/真 LLM。`_java_*` 风格的测试用
monkeypatch + httpx.MockTransport 模拟 Java 响应。
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import pytest

os.environ.setdefault("ANTHROPIC_API_KEY", "test-key")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")


# ---------------------------------------------------------------------------
# 测试用 FakeLLM —— 返回 AIMessage,不是 ChatResult(BaseChatModel.ainvoke 的
# 真实返回值是 AIMessage;ChatResult 是内部 _agenerate 的产物)。
# ---------------------------------------------------------------------------


def _make_fake_llm(reply_text: str):
    """构造一个返回给定 reply 的 FakeLLM。"""
    from langchain_core.messages import AIMessage

    class FakeLLM:
        async def ainvoke(self, msgs, **_):
            return AIMessage(content=reply_text)

    return FakeLLM()


# ---------------------------------------------------------------------------
# mock Java 的 httpx.AsyncClient 工厂 —— 必须先保存原引用,否则 patched
# lambda 会被递归调用。
# ---------------------------------------------------------------------------


def _patch_async_client(monkeypatch: pytest.MonkeyPatch, mod, handler) -> None:
    """把 mod.httpx.AsyncClient 替换成返回带 MockTransport 的真实 client。

    必须把原 httpx.AsyncClient 引用保存住,否则 patched lambda 里再调
    httpx.AsyncClient(...) 会无限递归。
    """
    import httpx
    transport = httpx.MockTransport(handler)
    real_async_client = httpx.AsyncClient

    def fake_factory(*args, **kwargs):
        return real_async_client(transport=transport, *args, **kwargs)

    monkeypatch.setattr(mod.httpx, "AsyncClient", fake_factory)


# ---------------------------------------------------------------------------
# M1-B: persona / system role
# ---------------------------------------------------------------------------


def test_persona_system_roles_has_all_four_personas() -> None:
    """4 张人设卡必须全部存在。"""
    from app.workflow.prompts import (
        DEFAULT_PERSONA,
        PERSONA_SYSTEM_ROLES,
    )

    expected = {"warm_strict", "friendly_tutor", "ielts_examiner", "patient_grandma"}
    assert set(PERSONA_SYSTEM_ROLES.keys()) == expected
    assert DEFAULT_PERSONA == "warm_strict"
    # 4 个 role 都不应为空,且应该互不相同
    roles = list(PERSONA_SYSTEM_ROLES.values())
    assert all(len(r) > 20 for r in roles)
    assert len(set(roles)) == 4


def test_system_role_for_known_and_unknown() -> None:
    from app.workflow.prompts import (
        DEFAULT_PERSONA,
        PERSONA_SYSTEM_ROLES,
        system_role_for,
    )

    # 已知 persona → 返回对应 role
    for name, role in PERSONA_SYSTEM_ROLES.items():
        assert system_role_for(name) == role

    # 未知 persona → 走默认
    assert system_role_for("nonexistent_persona") == PERSONA_SYSTEM_ROLES[DEFAULT_PERSONA]
    # None / 空串 → 走默认
    assert system_role_for(None) == PERSONA_SYSTEM_ROLES[DEFAULT_PERSONA]
    assert system_role_for("") == PERSONA_SYSTEM_ROLES[DEFAULT_PERSONA]


def test_compose_system_sentinel_prevents_double_injection() -> None:
    """Sentinel 标记避免多层 persona 叠加 + 切换 persona 时清掉旧 sentinel。"""
    from app.workflow.nodes.common import compose_system

    base = "You are a grammar checker."
    composed = compose_system(base, "warm_strict")
    assert "warm_strict" in composed
    assert "[[SYSTEM_ROLE:warm_strict]]" in composed

    # 再次注入同一个人设 → 不会重复,只是原样返回
    composed2 = compose_system(composed, "warm_strict")
    assert composed2 == composed
    assert composed2.count("[[SYSTEM_ROLE:warm_strict]]") == 1

    # 切换 persona → 旧 sentinel 会被清掉,只剩新 sentinel
    composed3 = compose_system(composed, "ielts_examiner")
    assert "[[SYSTEM_ROLE:ielts_examiner]]" in composed3
    # 不应该有 [[SYSTEM_ROLE:warm_strict]] 残留
    assert "[[SYSTEM_ROLE:warm_strict]]" not in composed3
    # 也不该有任何 SYSTEM_ROLE: 残留
    assert composed3.count("[[SYSTEM_ROLE:") == 1


def test_compose_system_no_persona_returns_base_unchanged() -> None:
    from app.workflow.nodes.common import compose_system

    base = "You are X."
    assert compose_system(base, None) == base
    assert compose_system(base, "") == base


# ---------------------------------------------------------------------------
# M1-B: scene 子目录加载
# ---------------------------------------------------------------------------


def test_resolve_prompt_path_scene_priority(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """scene 子目录优先,顶层兜底。"""
    from app.workflow import prompts as prompts_module

    fake = tmp_path / "prompts"
    (fake / "interview").mkdir(parents=True)
    (fake / "strategy_v2.txt").write_text("ROOT strategy v2", encoding="utf-8")
    (fake / "interview" / "strategy_v2.txt").write_text("INTERVIEW strategy v2", encoding="utf-8")
    # 顶层 v1 兜底
    (fake / "strategy_v1.txt").write_text("ROOT strategy v1", encoding="utf-8")

    monkeypatch.setattr(prompts_module, "_PROMPT_DIR", fake)

    # scene 指定 → 命中 interview/
    p = prompts_module._resolve_prompt_path("strategy", "v2", "interview")
    assert p is not None
    assert p.read_text(encoding="utf-8").strip() == "INTERVIEW strategy v2"

    # 换一个 scene → 没有 → 走顶层 v2
    p = prompts_module._resolve_prompt_path("strategy", "v2", "travel")
    assert p is not None
    assert p.read_text(encoding="utf-8").strip() == "ROOT strategy v2"

    # scene=None → 顶层
    p = prompts_module._resolve_prompt_path("strategy", "v2", None)
    assert p is not None
    assert p.read_text(encoding="utf-8").strip() == "ROOT strategy v2"


def test_resolve_prompt_path_returns_none_when_missing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from app.workflow import prompts as prompts_module

    fake = tmp_path / "prompts"
    fake.mkdir()
    monkeypatch.setattr(prompts_module, "_PROMPT_DIR", fake)

    assert prompts_module._resolve_prompt_path("nope", "v2", None) is None
    assert prompts_module._resolve_prompt_path("nope", "v2", "any") is None


def test_render_prompt_uses_scene_specific_v2() -> None:
    """端到端:render_prompt 应该用 scene 子目录的 v2 文件。"""
    from app.workflow import prompts as prompts_module

    # 利用真实仓库的 app/prompts/{scene}/ 目录(已存在)
    out = prompts_module.render_prompt(
        "strategy",
        {"SCENE": "interview", "TURN_COUNT": "1", "GRAMMAR": "70",
         "FLUENCY": "70", "VOCAB": "70", "LOGIC": "70",
         "CORRECTIONS": "[]", "ERROR_HISTORY": "[]", "PROFILE_COMMON_ERRORS": "[]"},
        default="unused",
        scene="interview",
    )
    # interview v2 的特征关键词
    assert "JOB INTERVIEW" in out or "interview" in out.lower()
    assert "STAR" in out  # interview 特有的 STAR 方法提示
    # root v2 没有 STAR 这种字眼
    assert out != prompts_module.render_prompt(
        "strategy",
        {"SCENE": "free_talk", "TURN_COUNT": "1", "GRAMMAR": "70",
         "FLUENCY": "70", "VOCAB": "70", "LOGIC": "70",
         "CORRECTIONS": "[]", "ERROR_HISTORY": "[]", "PROFILE_COMMON_ERRORS": "[]"},
        default="unused",
        scene="free_talk",
    )


# ---------------------------------------------------------------------------
# M1-B: chat_json persona 注入
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_chat_json_passes_persona_to_compose(monkeypatch: pytest.MonkeyPatch) -> None:
    """chat_json 应当把 persona 拼到 system 顶部。"""
    captured: dict[str, Any] = {}

    class CapturingLLM:
        async def ainvoke(self, msgs, **_):  # noqa: ANN001
            captured["messages"] = list(msgs)
            from langchain_core.messages import AIMessage
            return AIMessage(content='{"x": 1}')

    from app.workflow.nodes import common as common_module
    monkeypatch.setattr(common_module, "get_llm", lambda json_mode=True: CapturingLLM())

    raw = await common_module.chat_json(
        "You are a tutor.",
        "Q?",
        persona="ielts_examiner",
    )
    assert raw == {"x": 1}
    sys_msg = captured["messages"][0].content
    assert "[[SYSTEM_ROLE:ielts_examiner]]" in sys_msg
    assert "IELTS" in sys_msg  # role 文本特征词
    assert "You are a tutor." in sys_msg


@pytest.mark.asyncio
async def test_chat_json_without_persona_unchanged(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.workflow.nodes import common as common_module

    fake = _make_fake_llm('{"ok": true}')
    monkeypatch.setattr(common_module, "get_llm", lambda json_mode=True: fake)

    raw = await common_module.chat_json("Plain system prompt.", "Q?")
    assert raw == {"ok": True}


# ---------------------------------------------------------------------------
# M1-A: load_user_profile
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_load_user_profile_skips_on_empty_user_id() -> None:
    from app.workflow.nodes.load_user_profile import load_user_profile_node

    out = await load_user_profile_node({"user_id": "", "session_id": "s1"})
    assert out == {"user_id": "", "session_id": "s1"}
    assert "common_errors_from_profile" not in out


@pytest.mark.asyncio
async def test_load_user_profile_skips_on_non_numeric_user_id() -> None:
    from app.workflow.nodes.load_user_profile import load_user_profile_node

    out = await load_user_profile_node({"user_id": "uuid-abc-123", "session_id": "s1"})
    # 非 int user_id → 静默返回 state,profile 没加载
    assert "common_errors_from_profile" not in out


@pytest.mark.asyncio
async def test_load_user_profile_skips_when_java_returns_404(monkeypatch: pytest.MonkeyPatch) -> None:
    """Java 端没有该 user → exists=false → 静默返回。"""
    import httpx
    from app.workflow.nodes import load_user_profile as mod

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"exists": False, "userId": 42})

    transport = httpx.MockTransport(handler)
    monkeypatch.setattr(mod.httpx, "AsyncClient", lambda timeout: _MockAsyncClient(transport))

    out = await mod.load_user_profile_node({"user_id": "42", "session_id": "s1"})
    assert "common_errors_from_profile" not in out
    assert out.get("extra", {}).get("loaded_profile") is None


@pytest.mark.asyncio
async def test_load_user_profile_extracts_common_errors(monkeypatch: pytest.MonkeyPatch) -> None:
    """exists=true + commonErrors 列表 → 写入 common_errors_from_profile。"""
    from app.workflow.nodes import load_user_profile as mod

    def handler(request):
        import httpx
        return httpx.Response(
            200,
            json={
                "exists": True,
                "userId": 42,
                "grammarScore": 65,
                "vocabularyScore": 70,
                "fluencyScore": 60,
                "logicScore": 75,
                "cefrLevel": "B1",
                "commonErrors": ["i goed to the park", "i have went there"],
            },
        )

    _patch_async_client(monkeypatch, mod, handler)

    out = await mod.load_user_profile_node({"user_id": "42", "session_id": "s1"})
    assert out["common_errors_from_profile"] == [
        "i goed to the park",
        "i have went there",
    ]
    extra = out.get("extra", {})
    assert extra.get("loaded_profile", {}).get("cefr_level") == "B1"


class _MockAsyncClient:
    """httpx.AsyncClient 的最小 mock,接受 timeout 但用 mock transport。"""

    def __init__(self, transport: httpx.MockTransport) -> None:
        self._transport = transport
        # 让 with 语句能直接 yield 自己
        self._client = httpx.AsyncClient(transport=transport)

    async def __aenter__(self) -> httpx.AsyncClient:
        return self._client

    async def __aexit__(self, *args) -> None:
        await self._client.aclose()


# ---------------------------------------------------------------------------
# M1-A: write_error_book
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_write_error_book_no_corrections_passes_through() -> None:
    from app.workflow.nodes.write_error_book import write_error_book_node

    out = await write_error_book_node({"user_id": "1", "session_id": "s1", "turn_id": 1})
    assert out.get("user_id") == "1"
    assert "error_book_inserted" not in out.get("extra", {})


@pytest.mark.asyncio
async def test_write_error_book_no_user_id_skips() -> None:
    from app.workflow.nodes.write_error_book import write_error_book_node

    state = {
        "user_id": "",
        "session_id": "s1",
        "turn_id": 1,
        "corrections": [
            {"original": "foo", "corrected": "bar", "type": "grammar", "severity": "low"}
        ],
    }
    out = await write_error_book_node(state)
    # 没 user_id → 跳过,state 不变
    assert "error_book_inserted" not in out.get("extra", {})


@pytest.mark.asyncio
async def test_write_error_book_handles_java_failure_gracefully(monkeypatch: pytest.MonkeyPatch) -> None:
    """Java 调用失败 → 写 error 到 extra,主流程不阻断。"""
    from app.workflow.nodes import write_error_book as mod

    def handler(request):
        import httpx
        return httpx.Response(500, text="internal server error")

    _patch_async_client(monkeypatch, mod, handler)
    monkeypatch.setenv("JAVA_GATEWAY_URL", "http://stub")

    state = {
        "user_id": "1",
        "session_id": "s1",
        "turn_id": 1,
        "corrections": [
            {"original": "foo", "corrected": "bar", "type": "grammar", "severity": "low"}
        ],
    }
    out = await mod.write_error_book_node(state)
    extra = out.get("extra", {})
    # 失败 → 0 inserted,error 写了
    assert extra.get("error_book_inserted") == 0
    assert "error_book_error" in extra
    # 主流程没阻断
    assert out.get("user_id") == "1"


@pytest.mark.asyncio
async def test_write_error_book_records_inserted_count(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.workflow.nodes import write_error_book as mod

    def handler(request):
        import httpx
        return httpx.Response(200, json={"data": {"inserted": 3}})

    _patch_async_client(monkeypatch, mod, handler)
    monkeypatch.setenv("JAVA_GATEWAY_URL", "http://stub")

    state = {
        "user_id": "1",
        "session_id": "s1",
        "turn_id": 1,
        "corrections": [
            {"original": "a", "corrected": "b", "type": "grammar", "severity": "low"},
            {"original": "c", "corrected": "d", "type": "grammar", "severity": "low"},
        ],
    }
    out = await mod.write_error_book_node(state)
    assert out["extra"]["error_book_inserted"] == 3
    assert "error_book_error" not in out["extra"]


# ---------------------------------------------------------------------------
# M1-A: strategy_router 启发式 override
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_strategy_router_overrides_to_review_old_on_profile_errors(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """profile_common_errors 存在 + LLM 选了 normal_follow_up + 本轮无 high severity
    → 启发式必须 override 到 review_old_error。"""
    from app.workflow.nodes.strategy_router import strategy_router_node
    from app.workflow.nodes import common as common_module

    fake = _make_fake_llm('{"strategy":"normal_follow_up","reason":"stub"}')
    monkeypatch.setattr(common_module, "get_llm", lambda json_mode=True: fake)

    state = {
        "user_id": "1",
        "session_id": "s1",
        "turn_id": 1,
        "scene": "interview",
        "user_text": "I goed to the park yesterday.",
        "ability_score": {"grammar": 70, "vocabulary": 70, "fluency": 70, "logic": 70},
        "corrections": [
            {"original": "goed", "corrected": "went", "type": "grammar", "severity": "low"},
        ],
        "errors_this_session": [],
        "common_errors_from_profile": ["i have went there", "i goed to school"],
        "turn_count": 1,
        "coach_persona": "warm_strict",
    }
    out = await strategy_router_node(state)
    assert out["strategy"] == "review_old_error", (
        "启发式应该把 normal_follow_up 强制改成 review_old_error"
    )


@pytest.mark.asyncio
async def test_strategy_router_does_not_override_when_high_severity_correction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """本轮已有 high severity correction → 已经走 review_old,启发式不需要再覆盖。"""
    from app.workflow.nodes.strategy_router import strategy_router_node
    from app.workflow.nodes import common as common_module

    fake = _make_fake_llm('{"strategy":"normal_follow_up","reason":"stub"}')
    monkeypatch.setattr(common_module, "get_llm", lambda json_mode=True: fake)

    state = {
        "user_id": "1",
        "session_id": "s1",
        "turn_id": 1,
        "scene": "interview",
        "user_text": "I eated dinner.",
        "ability_score": {"grammar": 70, "vocabulary": 70, "fluency": 70, "logic": 70},
        "corrections": [
            {"original": "eated", "corrected": "ate", "type": "grammar", "severity": "high"},
        ],
        "errors_this_session": [],
        "common_errors_from_profile": ["i have went there"],
        "turn_count": 1,
        "coach_persona": "warm_strict",
    }
    out = await strategy_router_node(state)
    # 高 severity 在第一次 if 命中 review_old_error,LLM override 到 normal_follow_up,
    # 启发式条件 `not (corrections and any high)` 为假 → 不覆盖,最终 normal_follow_up
    assert out["strategy"] == "normal_follow_up"
