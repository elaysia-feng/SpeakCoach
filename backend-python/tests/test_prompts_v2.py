"""`render_prompt` 单元测试 —— 覆盖 v2 加载、占位符替换、v1 兜底。

不依赖网络 / LLM provider,毫秒级,跑在 CI 第一阶段。
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

# 在 import app.workflow.prompts 之前,确保配置初始化不会因为缺 key 失败。
os.environ.setdefault("ANTHROPIC_API_KEY", "test-key")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")


PROMPTS_DIR = Path(__file__).resolve().parents[1] / "app" / "prompts"


@pytest.fixture(autouse=True)
def _reset_load_prompt_cache():
    """`load_prompt` 是 lru_cache 的,每个 case 之间清一次避免互相污染。"""
    from app.workflow.prompts import load_prompt

    load_prompt.cache_clear()
    yield
    load_prompt.cache_clear()


def test_render_prompt_loads_v2_and_substitutes_placeholders() -> None:
    from app.workflow.prompts import render_prompt

    out = render_prompt(
        "hint",
        {"SCENE": "interview"},
        default="unused-default",
    )

    # 来自 ability_v2.txt / hint_v2.txt 的特征关键词必须出现
    assert "SpeakCoach" in out
    # ${SCENE} 必须被替换
    assert "${SCENE}" not in out
    assert "interview" in out
    # v2 的强约束签名必须保留
    assert "Return ONLY this JSON" in out
    # 默认 fallback 不应该出现 —— 因为 v2 文件存在
    assert "unused-default" not in out


def test_render_prompt_falls_back_to_v1_when_v2_missing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """v2 文件被删/重命名时,自动回退 v1 文本。"""
    # 在临时目录构造一个最小 prompts 目录,只有 v1。
    fake_dir = tmp_path / "prompts"
    fake_dir.mkdir()
    (fake_dir / "shadow_v1.txt").write_text(
        "v1 shadow template. User said: ${PLACEHOLDER}", encoding="utf-8"
    )
    (fake_dir / "shadow_v2.txt").write_text(
        "v2 shadow template.", encoding="utf-8"  # 故意不引用 ${PLACEHOLDER}
    )

    # 把 _PROMPT_DIR monkeypatch 到我们的临时目录
    from app.workflow import prompts as prompts_module

    monkeypatch.setattr(prompts_module, "_PROMPT_DIR", fake_dir)

    out = prompts_module.render_prompt(
        "shadow",
        {"PLACEHOLDER": "hello"},
        default="unused-default",
    )
    # v2 文件存在 → 应该用 v2,且不替换 ${PLACEHOLDER}(v2 没引用)
    assert "v2 shadow template" in out
    assert "v1 shadow template" not in out

    # 现在删掉 v2,应该回退 v1,这次会替换 ${PLACEHOLDER}
    (fake_dir / "shadow_v2.txt").unlink()
    out2 = prompts_module.render_prompt(
        "shadow",
        {"PLACEHOLDER": "hello"},
        default="unused-default",
    )
    assert "v1 shadow template" in out2
    assert "User said: hello" in out2
    assert "${PLACEHOLDER}" not in out2


def test_render_prompt_safe_substitute_does_not_raise_on_missing_key() -> None:
    """缺失 key 走 safe_substitute,原样保留 `${KEY}`,不抛 KeyError。"""
    from app.workflow.prompts import render_prompt

    # 拿 ability_v2,context 里只给 SCENE,不给 USER_TEXT / CORRECTIONS
    out = render_prompt(
        "ability",
        {"SCENE": "interview"},
        default="unused",
    )

    assert "interview" in out
    # 缺失的占位符应该原样保留(便于排错)
    assert "${USER_TEXT}" in out
    assert "${CORRECTIONS}" in out
    # 不能抛异常,out 是字符串
    assert isinstance(out, str)


def test_render_prompt_does_not_touch_json_braces() -> None:
    """JSON 文本里的 `{` `}` 不会被识别为占位符。"""
    from app.workflow.prompts import render_prompt

    out = render_prompt(
        "grammar",
        {"SCENE": "interview"},
        default="unused",
    )

    # 来自 v2 的 JSON schema 必须字面完整
    assert '{"original":' in out or '"original"' in out
    assert '"corrected"' in out
    assert '"type"' in out
    assert '"severity"' in out
    # 不能有 stray `}` 之类被吞掉
    assert out.count("{") >= 1
    assert out.count("}") >= 1


def test_all_v2_prompt_files_exist() -> None:
    """9 个 v2 文件全部存在;任何一个缺失 → 走 v1 兜底,行为退化。"""
    expected = {
        "ability_v2.txt",
        "grammar_v2.txt",
        "shadow_v2.txt",
        "strategy_v2.txt",
        "hint_v2.txt",
        "normal_v2.txt",
        "challenge_v2.txt",
        "review_old_v2.txt",
        "summary_v2.txt",
    }
    actual = {p.name for p in PROMPTS_DIR.glob("*_v2.txt")}
    missing = expected - actual
    assert not missing, f"Missing v2 prompt files: {missing}"


def test_v2_prompts_preserve_stub_keywords() -> None:
    """v2 prompts 必须含 `StubChatModel._stub_reply` 匹配的关键字。

    详见 `app/llm_factory.py:174-206`。如果丢了,offline test 会因为 stub
    返回 `{}` 而下游解析失败。
    """
    from app.workflow.prompts import render_prompt

    # grammar: 关键字 "correction" + "{"(JSON 围栏)
    grammar = render_prompt("grammar", {"SCENE": "interview"}, default="")
    assert "correction" in grammar.lower()
    assert "{" in grammar

    # shadow: 关键字 "shadow" 或 "upgraded"
    shadow = render_prompt("shadow", {"SCENE": "interview", "USER_TEXT": "hi", "CORRECTIONS": "[]"}, default="")
    assert "shadow" in shadow.lower() or "upgraded" in shadow.lower()

    # ability: 关键字 "ability" + "score"
    ability = render_prompt("ability", {"SCENE": "interview", "USER_TEXT": "hi", "CORRECTIONS": "[]"}, default="")
    assert "ability" in ability.lower()
    assert "score" in ability.lower()

    # strategy: 关键字 "strategy"
    strategy = render_prompt(
        "strategy",
        {
            "SCENE": "interview",
            "TURN_COUNT": "1",
            "GRAMMAR": "70", "FLUENCY": "70", "VOCAB": "70", "LOGIC": "70",
            "CORRECTIONS": "[]", "ERROR_HISTORY": "[]",
        },
        default="",
    )
    assert "strategy" in strategy.lower()

    # summary: 关键字 "summary"
    summary = render_prompt(
        "summary",
        {
            "SCENE": "interview",
            "TURN_COUNT": "1",
            "ERROR_COUNTS": "{}", "ABILITY_SCORE": "{}", "COMMON_ERRORS": "[]",
        },
        default="",
    )
    assert "summary" in summary.lower()

    # 4 个 followup: 关键字 "reply" 或 "question"
    for name in ("hint", "normal", "challenge", "review_old"):
        ctx = {"SCENE": "interview"}
        if name == "review_old":
            ctx.update({"OLD_ERROR": "x", "OLD_CORRECTION": "y", "USER_TEXT": "z"})
        text = render_prompt(name, ctx, default="")
        assert "reply" in text.lower() or "question" in text.lower(), (
            f"{name}_v2.txt missing 'reply'/'question' keyword — stub will return {{}}"
        )


def test_prompt_version_constant_is_v2() -> None:
    from app.workflow.prompts import PROMPT_VERSION

    assert PROMPT_VERSION == "v2"
