"""内部 FastAPI 契约的 smoke test。

通过 httpx.AsyncClient(transport=ASGITransport) 在进程内启动 FastAPI
应用，发起一个示例 turn 请求，并断言响应结构。
"""

from __future__ import annotations

import os
from pathlib import Path

# 允许在没有 .env 文件的情况下运行测试。
os.environ.setdefault("ANTHROPIC_API_KEY", "test-key")
os.environ.setdefault("ANTHROPIC_BASE_URL", "http://localhost:9999")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

import pytest
from httpx import ASGITransport, AsyncClient
from langchain_core.messages import AIMessage, HumanMessage


@pytest.mark.asyncio
async def test_health_ok() -> None:
    from app.main import app

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        r = await ac.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


@pytest.mark.asyncio
async def test_internal_turn_response_shape(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.routes import internal
    from app.main import app

    class FakeGraph:
        async def ainvoke(self, initial, config=None):  # noqa: ANN001
            assert initial["user_id"] == "2"
            assert initial["session_id"] == "694e327f-ac79-4964-9299-8d9de1f8a063"
            assert initial["thread_id"] == "thread:2:694e327f-ac79-4964-9299-8d9de1f8a063"
            assert config["configurable"]["thread_id"] == "thread:2:694e327f-ac79-4964-9299-8d9de1f8a063"
            return {
                "ai_reply": "Could you tell me more about the project?",
                "audio_url": "/api/audio/2/694e327f-ac79-4964-9299-8d9de1f8a063/turn_1.wav",
                "corrections": [
                    {
                        "original": "search document",
                        "corrected": "search documents",
                        "type": "grammar",
                        "severity": "medium",
                    }
                ],
                "shadow_answer": "I built a RAG project that can search documents.",
                "ability_score": {
                    "grammar": 72,
                    "vocabulary": 70,
                    "fluency": 68,
                    "logic": 75,
                    "pronunciation": 78,
                },
                "pronunciation_score": 78,
                "strategy": "normal_follow_up",
                "summary": None,
            }

    monkeypatch.setattr(internal, "get_graph", lambda: FakeGraph())

    payload = {
        "user_id": "2",
        "session_id": "694e327f-ac79-4964-9299-8d9de1f8a063",
        "turn_id": 1,
        "user_text": "Hello world",
        "scene": "daily_chat",
    }
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        r = await ac.post("/internal/turn", json=payload)
    assert r.status_code == 200, r.text
    body = r.json()
    java_fields = {
        "ai_reply",
        "audio_url",
        "corrections",
        "shadow_answer",
        "ability_score",
        "pronunciation_score",
        "strategy",
        "summary",
    }
    assert java_fields.issubset(body)
    assert body["ai_reply"] == "Could you tell me more about the project?"
    assert body["audio_url"].endswith("/turn_1.wav")
    assert isinstance(body["corrections"], str)
    assert body["corrections_list"][0]["corrected"] == "search documents"
    assert body["shadow_answer"] == "I built a RAG project that can search documents."
    assert body["ability_score"]["pronunciation"] == 78
    assert body["pronunciation_score"] == 78
    assert body["strategy"] == "normal_follow_up"


def test_workflow_nodes_are_split_by_file() -> None:
    root = Path(__file__).resolve().parents[1]
    workflow_dir = root / "app" / "workflow"
    nodes_dir = workflow_dir / "nodes"

    assert nodes_dir.is_dir()
    assert not (workflow_dir / "nodes.py").exists()

    expected_node_files = {
        "ability_analyze",
        "audit_log",
        "challenge_question",
        "generate_reply",
        "generate_summary",
        "grammar_check",
        "hint_question",
        "load_session_state",
        "normal_follow_up",
        "review_old_error",
        "save_report",
        "save_turn_state",
        "shadow_answer",
        "should_finish",
        "strategy_router",
        "tts_generate",
    }
    actual_files = {p.stem for p in nodes_dir.glob("*.py")}
    assert expected_node_files.issubset(actual_files)


def test_extract_json_accepts_trailing_commas() -> None:
    from app.workflow.parsers import extract_json

    assert extract_json('```json\n{"grammar": 72,}\n```') == {"grammar": 72}
    assert extract_json('extra text [{"a": 1,}] tail') == [{"a": 1}]


@pytest.mark.asyncio
async def test_save_turn_state_returns_only_new_messages() -> None:
    from app.workflow.nodes.save_turn_state import save_turn_state_node

    previous = [HumanMessage(content="old"), AIMessage(content="old reply")]
    result = await save_turn_state_node(
        {
            "messages": previous,
            "user_text": "new",
            "ai_reply": "new reply",
            "turn_id": 2,
            "strategy": "normal_follow_up",
            "audio_url": "/api/audio/1/s/turn_2.wav",
        }
    )

    assert [m.content for m in result["messages"]] == ["new", "new reply"]
    assert result["extra"]["saved_turn"]["turn_id"] == 2


@pytest.mark.asyncio
async def test_thread_id_format_matches_plan() -> None:
    """`thread:{user_id}:{session_id}` 是约定好的命名空间。"""
    from app.models import TurnRequest

    req = TurnRequest(user_id="alice", session_id="ses42", turn_id=1, user_text="hi")
    assert f"thread:{req.user_id}:{req.session_id}" == "thread:alice:ses42"


@pytest.mark.asyncio
async def test_langgraph_stub_turn_runs_end_to_end(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path,
) -> None:
    """真实 LangGraph 拓扑的 smoke test：即便使用 Stub LLM + TTS 回退也必须能跑通。"""
    monkeypatch.setenv("LLM_PROVIDER", "minimax")
    monkeypatch.delenv("MINIMAX_API_KEY", raising=False)
    monkeypatch.setenv("CHECKPOINT_BACKEND", "memory")
    monkeypatch.setenv("GPT_SOVITS_BASE_URL", "http://127.0.0.1:1")
    monkeypatch.setenv("AUDIO_STORAGE_PATH", str(tmp_path))
    monkeypatch.setenv("AUDIO_BASE_URL", "/api/audio")

    from app import config as app_config
    from app.workflow import graph as graph_module

    app_config._settings = None
    graph_module.get_graph.cache_clear()

    graph = graph_module.get_graph()
    final = await graph.ainvoke(
        {
            "user_id": "1",
            "session_id": "9",
            "turn_id": 1,
            "thread_id": "thread:1:9",
            "scene": "interview",
            "user_text": "I built a small agent project.",
            "messages": [],
        },
        config={"configurable": {"thread_id": "thread:1:9"}},
    )

    assert final["ai_reply"]
    assert final["audio_url"] == "/api/audio/1/9/turn_1.wav"
    assert final["strategy"] in {
        "hint_question",
        "normal_follow_up",
        "challenge_question",
        "review_old_error",
    }
    assert final["ability_score"]["pronunciation"] >= 0
    assert final["pronunciation_score"] >= 0
    assert len(final["messages"]) == 2
    assert (tmp_path / "1" / "9" / "turn_1.wav").exists()
