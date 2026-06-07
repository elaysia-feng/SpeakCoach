"""Prompt 加载器 —— 读取 `app/prompts/` 下带版本号的 .txt 文件。

加载器刻意做得防御性更强：若 prompt 文件缺失，则回退到硬编码的最小版本，
以保证 workflow 仍能继续运行。

`render_prompt` 在 `load_prompt` 之上加了版本感知和占位符替换：
- 先找 `{name}_v2.txt`，找不到回退到 `{name}_v1.txt`(走 `load_prompt`)
- 找到后用 `string.Template.safe_substitute(**context)` 替换 `${KEY}` 占位符
- safe_substitute 对缺失 key 不抛异常，未替换的 `${KEY}` 原样保留，便于排错
- 不缓存(每次带 context 调用都需要重新替换，而 context 不可哈希)

M1-B: 增加 scene + persona 维度。
- 优先查找 `app/prompts/{scene}/{name}_{version}.txt`
- 找不到回退到 `app/prompts/{name}_{version}.txt`
- scene/persona 还会在 system_role 前缀中注入(见 `system_role_for`)
"""

from __future__ import annotations

import logging
from functools import lru_cache
from pathlib import Path
from string import Template

logger = logging.getLogger(__name__)

_PROMPT_DIR = Path(__file__).resolve().parent.parent / "prompts"

# 当前主要 prompt 版本。bump 这里时同步：
#   1) 创建对应的 `*_v{N}.txt` 文件
#   2) 跑一遍 `tests/test_prompts_v2.py` 确认新版本能加载、占位符能替换
#   3) 真实跑一次 turn 检查 [AUDIT] 日志的 `prompt_v=` 字段
PROMPT_VERSION = "v2"


# ---------------------------------------------------------------------------
# M1-B: persona -> system role prefix
# ---------------------------------------------------------------------------
# 4 种人设的 system prompt 前缀，注入到 LLM system 消息顶部。
# 与 `frontend/src/components/PersonaPicker.tsx` 的 4 张人设卡对应。
PERSONA_SYSTEM_ROLES: dict[str, str] = {
    "warm_strict": (
        "You are a warm but strict English coach. "
        "Acknowledge what the learner did well, then point out the most important error. "
        "Keep replies short (1-3 sentences) and conversational."
    ),
    "friendly_tutor": (
        "You are a friendly, encouraging English tutor. "
        "Use short sentences, simple vocabulary, and a cheerful tone. "
        "Celebrate small wins. Avoid heavy grammar jargon."
    ),
    "ielts_examiner": (
        "You are an IELTS speaking examiner. "
        "Be formal, precise, and professional. "
        "Evaluate against IELTS band descriptors (fluency, lexical resource, grammatical range, "
        "pronunciation). Use phrases like 'Let us structure that with the STAR method', "
        "'Consider paraphrasing', or 'Try to extend your answer with a concrete example'."
    ),
    "patient_grandma": (
        "You are a patient, kind grandmother teaching her grandchild English. "
        "Speak slowly, use very simple words, and repeat encouragement. "
        "Use gentle phrases like 'Oh, that is close, dear — try again slowly', "
        "'Take your time, sweetheart', and 'Good try!'."
    ),
}

DEFAULT_PERSONA = "warm_strict"


def system_role_for(persona: str | None) -> str:
    """把 persona 映射到 system_role 前缀字符串；未知 persona 走默认。"""
    if not persona:
        return PERSONA_SYSTEM_ROLES[DEFAULT_PERSONA]
    return PERSONA_SYSTEM_ROLES.get(persona, PERSONA_SYSTEM_ROLES[DEFAULT_PERSONA])


# ---------------------------------------------------------------------------
# 路径解析 + 文件读取
# ---------------------------------------------------------------------------


def _resolve_prompt_path(name: str, version: str, scene: str | None) -> Path | None:
    """按 scene 子目录优先、顶层回退的策略定位 prompt 文件。

    - 文件名恒为 `{name}_{version}.txt`
    - 当 `name` 末尾已带 `_vN` 后缀时(例如调用方写 `load_prompt('grammar_v1', ...)`),
      自动剥掉,再用纯 `name` 重试一次,保证两种调用方式都能命中
      `app/prompts/{scene}/grammar_v1.txt` 形式的文件。
    """
    base_name = name
    suffix = f"_{version}"
    if base_name.endswith(suffix):
        base_name = base_name[: -len(suffix)]
    candidates: list[Path] = []
    if scene:
        candidates.append(_PROMPT_DIR / scene / f"{base_name}_{version}.txt")
        candidates.append(_PROMPT_DIR / scene / f"{name}_{version}.txt")
    candidates.append(_PROMPT_DIR / f"{base_name}_{version}.txt")
    candidates.append(_PROMPT_DIR / f"{name}_{version}.txt")
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


@lru_cache(maxsize=64)
def load_prompt(name: str, default: str = "", scene: str | None = None) -> str:
    """按名称加载 v1 prompt 模板（不带 `_v1.txt` 后缀）。

    - `scene` 非空时优先查找 `app/prompts/{scene}/{name}_v1.txt`
    - 找不到回退到 `app/prompts/{name}_v1.txt`
    - 都不存在返回 default 并打 warning
    """
    path = _resolve_prompt_path(name, "v1", scene)
    if path is None:
        logger.warning(
            "Prompt %s (scene=%s) not found under %s — using fallback",
            name, scene, _PROMPT_DIR,
        )
        return default
    try:
        return path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        logger.warning("Failed to read prompt %s: %s — using fallback", name, exc)
        return default


def render_prompt(
    name: str,
    context: dict | None = None,
    default: str = "",
    scene: str | None = None,
) -> str:
    """加载 `{name}_v2.txt`(缺则回退 v1),用 `context` 替换 `${KEY}` 占位符。

    - JSON 文本里的 `{` `}` 不会被误识别，因为 `string.Template` 只识别 `$VAR`。
    - 缺失的 context key 走 `safe_substitute`，原样保留 `${KEY}`，不抛异常。
    - 若 v2 文件不存在但 v1 存在，v1 内容里的 `${KEY}` 不会自动替换(因为 v1
      用的是其它占位符格式)；这种情况要靠调用方自己 `.replace()` 或视为 OK。
    - `scene` 非空时优先尝试 `app/prompts/{scene}/{name}_v2.txt`，再回退。
    """
    ctx = context or {}
    for version in ("v2", "v1"):
        path = _resolve_prompt_path(name, version, scene)
        if path is None:
            continue
        try:
            text = path.read_text(encoding="utf-8").strip()
            return Template(text).safe_substitute(**ctx)
        except OSError as exc:
            logger.warning("Failed to read prompt %s: %s — falling back", name, exc)

    # v1 兜底走 load_prompt(scene=...)
    return load_prompt(name, default=default, scene=scene)


__all__ = [
    "load_prompt",
    "render_prompt",
    "system_role_for",
    "PERSONA_SYSTEM_ROLES",
    "DEFAULT_PERSONA",
    "PROMPT_VERSION",
]
