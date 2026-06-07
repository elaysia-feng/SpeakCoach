"""GPT-SoVITS HTTP 客户端 + 静音回退。

`synthesize(text, user_id, session_id, turn_id, out_path)` 会：
  1. 将文本 POST 到 `{gpt_sovits_base_url}/tts`。
  2. 把返回的 WAV 字节写入 `out_path`。
  3. 在任何网络 / HTTP 失败时，向 `out_path` 写入一个最小且合法的 PCM 静音 WAV，
     以保证调用方（workflow / Java 代理）始终能拿到可播放的文件。
  4. 返回写入文件的绝对路径。

本模块是 Python 服务（写入方）与 Java `AudioController`（读取方）所用
磁盘音频布局的唯一真理来源。
"""

from __future__ import annotations

import logging
import re
import time
import wave
from pathlib import Path
from typing import Optional

import requests

from .config import Settings, get_settings

logger = logging.getLogger(__name__)

# Use ``requests`` (urllib3) instead of httpx. Verified 2026-06-06:
# ``curl`` / ``urllib`` / ``requests`` all return 200 + RIFF WAV from
# GPT-SoVITS 9880, but ``httpx 0.28.x`` (h11 transport) makes it return
# 502 + empty body. Same body, same URL. Switched to urllib3.
_DEFAULT_TIMEOUT = (5.0, 20.0)  # (connect, read) — seconds
_SESSION = requests.Session()


# ---------------------------------------------------------------------------
# 公共 API
# ---------------------------------------------------------------------------


def audio_url_for(user_id: str, session_id: str, turn_id: int) -> str:
    """构造 Java 网关回放音频时使用的 URL。"""
    cfg = get_settings()
    base = (cfg.audio_base_url or "/api/audio").rstrip("/")
    return f"{base}/{_safe(user_id)}/{_safe(session_id)}/turn_{int(turn_id)}.wav"


def audio_path_for(user_id: str, session_id: str, turn_id: int) -> Path:
    """构造音频文件的磁盘绝对路径。会按需创建父目录。"""
    cfg = get_settings()
    base = Path(cfg.audio_storage_path).expanduser().resolve()
    out = base / _safe(user_id) / _safe(session_id) / f"turn_{int(turn_id)}.wav"
    out.parent.mkdir(parents=True, exist_ok=True)
    return out


def synthesize(
    text: str,
    user_id: str,
    session_id: str,
    turn_id: int,
    *,
    settings: Optional[Settings] = None,
) -> Optional[str]:
    """将 `text` 合成为 WAV。成功时返回公共音频 URL。

    参考音色始终取自 `cfg.tts_ref_audio_path`。遇到任何上游错误都会回退
    到静音 WAV；网络错误不会抛异常，但若本地写入静音回退文件本身失败，
    则会重新抛出 ``OSError``（以便调用方接管错误处理）。
    """
    cfg = settings or get_settings()
    if not text or not text.strip():
        return None
    if not user_id or not session_id:
        return None

    out_path = audio_path_for(user_id, session_id, turn_id)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    # Body shape mirrors Luvia backend (TtsServiceImpl.convertTextToSpeech):
    # 7 fields, no streaming_mode. GPT-SoVITS' Pydantic model accepts it as
    # default, but sending it produced 400 "There was an error parsing the body"
    # on this server. Trim to the minimum that Luvia sends.
    body = {
        "text": text,
        "text_lang": cfg.tts_text_language or "en",
        "ref_audio_path": cfg.tts_ref_audio_path or "",
        "prompt_text": cfg.tts_prompt_text or "",
        "prompt_lang": cfg.tts_prompt_language or cfg.tts_text_language or "en",
        "speed_factor": cfg.tts_speed,
        "media_type": "wav",
    }

    headers: dict[str, str] = {}
    if cfg.gpt_sovits_api_key:
        headers["Authorization"] = f"Bearer {cfg.gpt_sovits_api_key}"

    base_url = (cfg.gpt_sovits_base_url or "").rstrip("/")
    try:
        wav_bytes = _request_tts(f"{base_url}/tts", body, headers)
    except Exception as exc:  # noqa: BLE001 — fallback path, log only
        logger.warning(
            "GPT-SoVITS synthesis failed (user=%s session=%s turn=%s): %s — writing silence fallback",
            user_id, session_id, turn_id, exc,
        )
        # 契约：静音回退成功时返回公共 URL，与成功路径的返回值保持一致。
        # 遇到 OSError 时重新抛出，以便调用方接管错误处理
        # （返回值不再与文件不存在时的 None 混在一起）。
        _write_silence_wav(out_path, millis=400)
        return audio_url_for(user_id, session_id, turn_id)

    out_path.write_bytes(wav_bytes)
    logger.info(
        "TTS ok: user=%s session=%s turn=%s bytes=%d url=%s",
        user_id, session_id, turn_id, len(wav_bytes), audio_url_for(user_id, session_id, turn_id),
    )
    return audio_url_for(user_id, session_id, turn_id)


# ---------------------------------------------------------------------------
# 内部辅助函数
# ---------------------------------------------------------------------------


def _safe(segment: str) -> str:
    """从一个 URL 段中剥离路径分隔符和路径穿越字符。

    与 Java 的行为一致：把任何连续的正/反斜杠以及任何字面意义上的 ``..``
    路径穿越都折叠为单个下划线。单独的点会被保留。
    """
    if not segment:
        return "_"
    return re.sub(r"[/\\]+|\.\.", "_", segment).strip() or "_"


def _request_tts(url: str, body: dict[str, object], headers: dict[str, str]) -> bytes:
    """POST 到 GPT-SoVITS,带 5xx 重试 + 指数退避。

    GPT-SoVITS v2 在 cuda OOM / torch 内部 crash 时,Python try/except 抓不到,
    uvicorn 会关连接并返 502 + 空 body (worker 已死)。这里在重试前 sleep
    稍长一点,给 GPT-SoVITS 时间从瞬时 CUDA 错误中恢复。
    """
    last_exc: Exception | None = None
    for attempt in range(3):
        try:
            resp = _SESSION.post(url, json=body, headers=headers, timeout=_DEFAULT_TIMEOUT)
            resp.raise_for_status()
            wav_bytes = resp.content
            if not wav_bytes or len(wav_bytes) < 44:
                raise ValueError(f"GPT-SoVITS returned {len(wav_bytes) if wav_bytes else 0} bytes")
            return wav_bytes
        except (requests.Timeout, requests.ConnectionError, requests.HTTPError) as exc:
            last_exc = exc
            # 5xx (esp. 502 = worker 死了) 给更长的退避,给 GPU 喘息
            is_5xx = isinstance(exc, requests.HTTPError) and 500 <= exc.response.status_code < 600
            backoff = 1.0 * (2**attempt) if is_5xx else 0.2 * (2**attempt)
            if attempt < 2:
                logger.warning(
                    "GPT-SoVITS attempt %d/3 failed (%s: %s); retrying in %.1fs",
                    attempt + 1, type(exc).__name__, exc, backoff,
                )
                time.sleep(backoff)
                continue
            raise
    if last_exc is not None:
        raise last_exc
    raise RuntimeError("GPT-SoVITS request failed")


def _write_silence_wav(path: Path, millis: int = 250) -> None:
    """写入一个最小且合法的静音 PCM WAV（16 kHz / 16-bit / 单声道）。"""
    sample_rate = 16_000
    channels = 1
    sample_width = 2  # 字节（16-bit）
    n_samples = max(1, int(sample_rate * millis / 1000))
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(sample_width)
        wf.setframerate(sample_rate)
        wf.writeframes(b"\x00" * (n_samples * channels * sample_width))


# 重新导出静音 WAV 字节，供需要断言回退形状的测试使用。
def silence_wav_bytes(millis: int = 250) -> bytes:
    sample_rate = 16_000
    channels = 1
    bits_per_sample = 16
    byte_rate = sample_rate * channels * bits_per_sample // 8
    block_align = channels * bits_per_sample // 8
    n_samples = max(1, int(sample_rate * millis / 1000))
    data_size = n_samples * channels * (bits_per_sample // 8)
    import io

    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(bits_per_sample // 8)
        wf.setframerate(sample_rate)
        wf.writeframes(b"\x00" * data_size)
    return buf.getvalue()


__all__ = ["synthesize", "audio_url_for", "audio_path_for", "silence_wav_bytes"]
