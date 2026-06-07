"""用户录音转写与口语指标分析。

优先使用 WhisperX 做转写和单词级对齐；本地未安装 WhisperX 或模型失败时，
回退到前端提交的浏览器转写文本，保证开发环境仍可跑通完整 turn 链路。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any

from .config import get_settings

logger = logging.getLogger(__name__)


@dataclass
class SpeechAnalysis:
    transcript: str = ""
    word_timestamps: list[dict[str, Any]] = field(default_factory=list)
    speech_metrics: dict[str, Any] = field(default_factory=dict)


def analyze_audio(
    audio_path: str,
    client_transcript: str = "",
    practice_mode: str = "",
    practice_target: str = "",
) -> SpeechAnalysis:
    """返回转写、单词时间戳和口语指标。"""
    path = Path(audio_path)
    if not path.exists() or not path.is_file():
        logger.warning("Audio file not found for speech analysis: %s", audio_path)
        return with_practice_metrics(
            fallback_analysis(client_transcript, "audio_not_found"),
            practice_mode,
            practice_target,
        )

    try:
        analysis = whisperx_analysis(str(path))
        if analysis.transcript.strip():
            return with_practice_metrics(analysis, practice_mode, practice_target)
    except Exception as exc:  # noqa: BLE001
        logger.warning("WhisperX analysis failed, falling back to client transcript: %s", exc)

    return with_practice_metrics(
        fallback_analysis(client_transcript, "whisperx_unavailable"),
        practice_mode,
        practice_target,
    )


def whisperx_analysis(audio_path: str) -> SpeechAnalysis:
    """调用 WhisperX 完成转写和单词级对齐。"""
    import whisperx  # type: ignore[import-not-found]

    settings = get_settings()
    model = load_whisperx_model(
        settings.whisperx_model,
        settings.whisperx_device,
        settings.whisperx_compute_type,
    )
    result = model.transcribe(audio_path, batch_size=settings.whisperx_batch_size)
    segments = result.get("segments", []) or []
    language = result.get("language", "en") or "en"

    try:
        align_model, metadata = load_align_model(language, settings.whisperx_device)
        aligned = whisperx.align(
            segments,
            align_model,
            metadata,
            audio_path,
            settings.whisperx_device,
            return_char_alignments=False,
        )
        segments = aligned.get("segments", segments) or []
    except Exception as exc:  # noqa: BLE001
        logger.warning("WhisperX alignment failed; using segment transcript only: %s", exc)

    transcript = " ".join((seg.get("text") or "").strip() for seg in segments).strip()
    words = extract_words(segments)
    return SpeechAnalysis(
        transcript=transcript,
        word_timestamps=words,
        speech_metrics=calculate_metrics(transcript, words, source="whisperx"),
    )


@lru_cache(maxsize=2)
def load_whisperx_model(model_name: str, device: str, compute_type: str) -> Any:
    import whisperx  # type: ignore[import-not-found]

    return whisperx.load_model(model_name, device=device, compute_type=compute_type)


@lru_cache(maxsize=4)
def load_align_model(language_code: str, device: str) -> tuple[Any, Any]:
    import whisperx  # type: ignore[import-not-found]

    return whisperx.load_align_model(language_code=language_code, device=device)


def extract_words(segments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    words: list[dict[str, Any]] = []
    for seg in segments:
        for item in seg.get("words", []) or []:
            word = str(item.get("word", "")).strip()
            if not word:
                continue
            start = safe_float(item.get("start"))
            end = safe_float(item.get("end"))
            if start is None or end is None or end < start:
                continue
            words.append({"word": word, "start": round(start, 3), "end": round(end, 3)})
    return words


def fallback_analysis(client_transcript: str, source: str) -> SpeechAnalysis:
    transcript = (client_transcript or "").strip()
    words = approximate_words(transcript)
    return SpeechAnalysis(
        transcript=transcript,
        word_timestamps=words,
        speech_metrics=calculate_metrics(transcript, words, source=source),
    )


def approximate_words(transcript: str) -> list[dict[str, Any]]:
    words = [w for w in transcript.split() if w]
    return [
        {"word": word, "start": round(index * 0.5, 3), "end": round(index * 0.5 + 0.4, 3)}
        for index, word in enumerate(words)
    ]


def calculate_metrics(transcript: str, words: list[dict[str, Any]], source: str) -> dict[str, Any]:
    word_count = len(words) if words else len([w for w in transcript.split() if w])
    if words:
        start = safe_float(words[0].get("start")) or 0.0
        end = safe_float(words[-1].get("end")) or start
        duration_seconds = max(0.1, end - start)
    else:
        duration_seconds = max(0.1, word_count * 0.5)

    pauses: list[dict[str, Any]] = []
    for prev, curr in zip(words, words[1:]):
        prev_end = safe_float(prev.get("end"))
        curr_start = safe_float(curr.get("start"))
        if prev_end is None or curr_start is None:
            continue
        gap = curr_start - prev_end
        if gap >= 0.7:
            pauses.append({
                "after": prev.get("word", ""),
                "before": curr.get("word", ""),
                "duration": round(gap, 3),
            })

    words_per_minute = int(round(word_count / duration_seconds * 60)) if word_count else 0
    pause_penalty = min(25, len(pauses) * 5)
    speed_penalty = 0
    if words_per_minute and words_per_minute < 80:
        speed_penalty = min(20, 80 - words_per_minute)
    elif words_per_minute > 180:
        speed_penalty = min(20, words_per_minute - 180)

    completeness = 100 if transcript.strip() else 0
    fluency_score = clamp_score(88 - pause_penalty - speed_penalty if word_count else 0)
    pronunciation_score = clamp_score((fluency_score + completeness) // 2 if word_count else 0)

    return {
        "source": source,
        "word_count": word_count,
        "duration_seconds": round(duration_seconds, 2),
        "words_per_minute": words_per_minute,
        "pause_count": len(pauses),
        "pauses": pauses,
        "completeness": completeness,
        "fluency_score": fluency_score,
        "pronunciation_score": pronunciation_score,
    }


def with_practice_metrics(
    analysis: SpeechAnalysis,
    practice_mode: str,
    practice_target: str,
) -> SpeechAnalysis:
    """在语音指标中追加跟读练习指标；普通对话不受影响。"""
    mode = (practice_mode or "").strip().lower()
    target = (practice_target or "").strip()
    if mode != "shadowing" or not target:
        return analysis

    metrics = dict(analysis.speech_metrics)
    metrics["practice_mode"] = "shadowing"
    metrics["practice_target"] = target
    metrics["shadow_similarity_score"] = shadow_similarity_score(analysis.transcript, target)
    analysis.speech_metrics = metrics
    return analysis


def shadow_similarity_score(transcript: str, target: str) -> int:
    """用轻量词级匹配计算跟读相似度，避免引入额外模型。"""
    spoken = normalize_words(transcript)
    expected = normalize_words(target)
    if not spoken or not expected:
        return 0

    expected_counts: dict[str, int] = {}
    for word in expected:
        expected_counts[word] = expected_counts.get(word, 0) + 1

    overlap = 0
    remaining = dict(expected_counts)
    for word in spoken:
        count = remaining.get(word, 0)
        if count <= 0:
            continue
        overlap += 1
        remaining[word] = count - 1

    ordered = longest_ordered_match(spoken, expected)
    coverage_score = overlap / len(expected) * 70
    order_score = ordered / len(expected) * 30
    return clamp_score(int(round(coverage_score + order_score)))


def normalize_words(text: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", (text or "").lower())


def longest_ordered_match(spoken: list[str], expected: list[str]) -> int:
    """返回 spoken 与 expected 的最长公共子序列长度。"""
    if not spoken or not expected:
        return 0
    prev = [0] * (len(expected) + 1)
    for spoken_word in spoken:
        curr = [0]
        for index, expected_word in enumerate(expected, start=1):
            if spoken_word == expected_word:
                curr.append(prev[index - 1] + 1)
            else:
                curr.append(max(curr[-1], prev[index]))
        prev = curr
    return prev[-1]


def safe_float(value: Any) -> float | None:
    try:
        if value is None:
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def clamp_score(value: int) -> int:
    return max(0, min(100, int(value)))
