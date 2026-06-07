# SpeakCoach

> **AI 英语口语陪练 Agent。**
> 浏览器 (React) → Java Spring Boot 网关 → Python FastAPI + LangGraph 工作流 → **GPT-SoVITS 本地 TTS（可以下载代码下来将GTPSOVICE变成自己训练的语音模型， 实现和自己喜欢的角色训练）** → MySQL（长期记忆）+ Redis（LangGraph checkpoint）。

---

##  项目亮点

### 1.  4×4 网格化的「自定义角色」系统

SpeakCoach 把"AI 陪练老师"拆成两个独立维度，可以自由叠加：

- **场景（Scene）×4**：`interview`（求职面试）、`travel`（出行旅游）、`daily_chat`（日常闲聊）、`business`（商务英语）。
- **人设（Persona）×4**：`warm_strict`、`friendly_tutor`、`ielts_examiner`、`patient_grandma`。

> 理论上一台部署就能组合出 **16 种不同人设的 AI 陪练**——你既可以选"商务英语 × 雅思考官"做严肃模拟面试，也可以选"日常闲聊 × 奶奶"做零压力口语热身。

人设通过 `PERSONA_SYSTEM_ROLES` 字典注入到 LLM 的 system 消息头部，定义语气、词汇、句长、纠错风格，详见 `backend-python/app/workflow/prompts.py`。

### 2.  版本化的 Prompt 仓库

每个场景都有独立的 prompt 目录，按"角色"分文件并打版本号：

```
backend-python/app/prompts/
├── interview/   # ability / challenge / grammar / hint / normal / shadow / strategy / summary   (_v1, _v2)
├── travel/      # ability / grammar / shadow / strategy   (_v1, _v2)
├── daily_chat/  # ability / grammar / shadow / strategy   (_v1, _v2)
├── business/    # ability / grammar / shadow / strategy   (_v1, _v2)
└── ...
```

- **场景优先**：`prompts/{scene}/{role}_v{N}.txt` 找不到时回退到 `prompts/{role}_v{N}.txt`，保证新场景不会因为缺文件而崩。
- **版本优先**：`render_prompt` 先找 `*_v2.txt`，找不到回退到 `*_v1.txt`。改 prompt 时只要新增一个 `*_v3.txt` 并把 `PROMPT_VERSION = "v2"` 改成 `"v3"` 即可灰度切换。
- **占位符渲染**：`string.Template.safe_substitute`，缺失的 `${KEY}` 不会抛异常而是原样保留，方便排错。

### 3.  GPT-SoVITS 本地 TTS + 静音兜底

`synthesize(text, user_id, session_id, turn_id)` 是 TTS 客户端的唯一入口：

- 文本 POST 到 `GPT_SOVITS_BASE_URL/tts`，返回的 WAV 直接落盘 `storage/audio/{user_id}/{session_id}/turn_{n}.wav`。
- 网络/服务故障时**自动回退到一段 400 ms 的合法 PCM 静音 WAV**，调用方永远能拿到可播放的文件，方便开发与冒烟测试。
- **支持自定义参考音色**：通过 `.env` 里的 `tts_ref_audio_path` 指向任何你训练好的 GPT-SoVITS 参考音频文件，就能让"奶奶"用你的声线说话。
- 落盘布局是 Python 写入方与 Java `AudioController` 读取方之间**唯一的事实来源**（见 `audio_path_for` / `audio_url_for`）。

### 4.  多 LLM Provider 热插拔

`backend-python/app/llm_factory.py` 统一抽象：

- `openai`（真正 OpenAI 或任意 OpenAI 兼容端点）
- `anthropic`（Anthropic Claude）
- `minimax`（MiniMax 网关，封装为 ChatAnthropic）
- 任意 provider 缺 API key 时，自动降级为 `StubChatModel`，让 workflow 在无 key 环境下也能跑通端到端测试。

切换 provider 只需在 `.env` 改 `LLM_PROVIDER=`，无需改业务代码。

### 5.  LangGraph 长程记忆 + 可观测

- `thread_id = thread:{user_id}:{session_id}` 强保证每个用户的 checkpoint 隔离。
- `CHECKPOINT_BACKEND=memory|redis` 一键切换（默认内存，Redis 7+ 持久化）。
- 每个 LangGraph 节点都写一条 `audit_log`（输入 JSON / 输出 JSON / latency_ms / model_name / prompt_version），方便后续接 Java 的 `/internal/audit` 端点。

---

## 技术栈

| 层 | 选型 | 端口 |
|---|---|---|
| 前端 | React 19 + Vite + TypeScript | `5173` |
| 网关 / 业务 | Spring Boot 3.3.4 + JDK 21 + Lombok 1.18.34 + MyBatis-Plus | `8080` |
| AI 工作流 | FastAPI + LangGraph + langchain-openai / langchain-anthropic | `9000`（内网） |
| TTS | GPT-SoVITS（本地推理） | `9880` |
| 关系库 | MySQL 8.0（`speakcoach` 库） | `3306` |
| 缓存 / Checkpoint | Redis 7+ | `6379` |
| 鉴权 | JWT（HS256） | — |

---

## 仓库布局

```
SpeakCoach/
├── backend-java/      Spring Boot 3 网关 + 鉴权 + 业务 + AudioController
├── backend-python/    FastAPI + LangGraph 工作流 + GPT-SoVITS 客户端
│   └── app/prompts/   版本化 prompt 仓库（场景 × 角色 × 版本）
├── frontend/          React + Vite + TypeScript
├── db/                MySQL 初始化脚本（init.sql）
├── storage/audio/     TTS 生成的 WAV 文件（按 userId/sessionId 分目录）
├── e2e/               端到端冒烟测试
├── .omc/              OMC 编排系统的 plan / handoff / research（不入版本库已 ignore）
├── AGENTS.md          架构 / 包图 / 服务契约（详细文档，先读它）
└── README.md          ← 你正在看这个
```

---

## 架构硬规则

1. **前端只连 Java**。Python 是内部服务，浏览器永远不直接打到 `:9000`。
2. Java 在每个 `/api/chat` 和 `/api/audio/**` 上都校验 `session.userId == currentUserId`。
3. LangGraph `thread_id` 永远是 `thread:{user_id}:{session_id}`，确保多用户 checkpoint 不串。
4. 每个 LangGraph 节点都写 `audit_log`（input/output JSON + latency_ms + model_name + prompt_version）。
5. 音频由 Python 生成、落盘到 `storage/audio/`，由 Java 鉴权后再返回，URL 形如 `/api/audio/{userId}/{sessionId}/turn_{n}.wav`。
6. **必须 JDK 21**（Lombok 1.18.34 + Spring Boot 3.3.4）。JDK 17 会 `IncompatibleClassChangeError`，且崩溃时会在 `backend-java/` 留下 `hs_err_pid*.log`。
7. 持久层是 **MyBatis-Plus**（Mapper 继承 `BaseMapper<T>`），不是 JPA。`userId` 是自增 `BIGINT`，`sessionId` 是 UUID 字符串。

---

##  前置依赖（原生本地安装，不依赖 Docker）

> **JDK 21 是硬性要求。** Lombok 1.18.34 + Spring Boot 3.3.4 在 JDK 17 上会编译失败 / 运行时崩溃。
> 验证当前 JDK：
>
> ```bash
> java -version
> # → openjdk version "21.x.x" ...
> ```
> 如果装了多个 JDK，把 `JAVA_HOME` 指向 JDK 21。

| 工具 | 版本 | 备注 |
|---|---|---|
| Java | 21+ | `java -version` 必须 21 |
| Maven | 3.9+ | 或用自带的 `./mvnw` |
| Python | 3.11+ | 推荐 venv |
| Node.js | 20+ | LTS |
| MySQL | 8.0+ | 监听 `localhost:3306`，库名 `speakcoach` |
| Redis | 7+ | 可选（生产/共享 checkpoint 才需要） |
| GPT-SoVITS | latest | 本地推理服务，`.env` 里配 base URL |

### Windows 一键安装

```powershell
# MySQL
winget install Oracle.MySQL
# Redis（Windows 下的原生 Redis，推荐 Memurai）
winget install Memurai.Memurai

# MySQL 启动后导入 schema
mysql -uroot -p < db/init.sql
```

---

## 启动整个栈

需要开 3 个终端：

```bash
# 终端 1 — Java 网关
cd backend-java
# 确保 JDK 21 生效
$env:JAVA_HOME = "E:\develop\java\jdk-21"
mvn clean compile -DskipTests
mvn spring-boot:run
# → http://localhost:8080

# 终端 2 — Python AI 服务
cd backend-python
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env       # 然后编辑填 key
uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
# → http://localhost:9000（仅内网）

# 终端 3 — React 前端
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

---

##  环境变量

### `backend-java/src/main/resources/application.yml`

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/speakcoach
    username: root
    password: YOUR_PASSWORD
  redis:
    host: localhost
    port: 6379
python:
  service:
    base-url: http://localhost:9000
jwt:
  secret: change-me-in-production   # 生产环境务必改
```

### `backend-python/.env`

```dotenv
# LLM
LLM_PROVIDER=openai                 # openai | anthropic | minimax
LLM_MODEL=gpt-4o-mini
OPENAI_API_KEY=sk-...               # anthropic 用 ANTHROPIC_API_KEY，minimax 用 MINIMAX_API_KEY

# TTS（GPT-SoVITS 本地推理）
GPT_SOVITS_BASE_URL=http://localhost:9880
TTS_REF_AUDIO_PATH=./ref_voices/default.wav   # 自定义角色音色的参考音频

# 音频落盘
AUDIO_STORAGE_PATH=../storage/audio
AUDIO_BASE_URL=/api/audio

# LangGraph checkpoint
CHECKPOINT_BACKEND=memory           # memory | redis
REDIS_URL=redis://localhost:6379/0
```

### `frontend/.env`

```dotenv
VITE_API_BASE=http://localhost:8080
```

---

## 测试

完整 curl 串在 `AGENTS.md` §7。精简版：

```bash
# 1. 注册（MyBatis-Plus：email 必填，userId 自增 Long）
curl -i -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"smoketest","email":"smoketest@e2e.local","password":"test1234"}'
# → 201 {userId, username, email, token}

# 2. 登录
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"smoketest","email":"smoketest@e2e.local","password":"test1234"}'
# → 200 {...}

# 3. /me 不带 / 带 token
curl -i http://localhost:8080/api/auth/me                                               # → 401
curl -i http://localhost:8080/api/auth/me -H "Authorization: Bearer <token>"           # → 200

# 4. 建会话
curl -i -X POST http://localhost:8080/api/sessions \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"scene":"interview"}'                                                            # → 201

# 5. 对话（Java → Python :9000）
curl -i -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"<sessionId>\",\"userText\":\"Hello, my name is John.\"}"         # → 200

# 6. 结束会话
curl -i -X POST http://localhost:8080/api/sessions/<sessionId>/finish \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"summary":"Smoke test complete."}'                                               # → 200

# 7. 音频未鉴权访问应被拒
curl -i http://localhost:8080/api/audio/1/<sessionId>/turn_1.wav                         # → 401 / 403
```

---

## 已知限制

完整列表见 `AGENTS.md` §8，要点：

- GPT-SoVITS 不可达时 TTS 自动回退到 400 ms 静音 WAV，保证调用方不抛异常。
- 没配 LLM API key 时自动降级到 `StubChatModel`，workflow 端到端可跑，回复是占位文本。
- LangGraph 默认内存 checkpoint（保底机制，如果没有开启redis的话就默认为本地内存存储）；想跨进程共享就设 `CHECKPOINT_BACKEND=redis`。
- **Maven 构建硬性要求 JDK 21**（Lombok 1.18.34 / Spring Boot 3.3.4）。JDK 17 不行。
- `application.yml` 自带的数据库密码和 JWT secret 是开发占位，**生产部署前必须覆盖**。

---

## 进度

- 实现：端到端可跑通，Java 干净编译、Python 启动正常、所有内部端点响应正常、TTS 在 GPT-SoVITS 离线时回退静音
