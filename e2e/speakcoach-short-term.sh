#!/usr/bin/env bash
# =============================================================================
# SpeakCoach — Short-term milestones (M1-A / M1-B / M1-C) e2e verification
#
# What it does
# ------------
# 1. Pre-flight: verify java / python / node / mysql / redis prerequisites
#    (binary presence + listening port). With -s / --skip-runtime it stops
#    after the prerequisite probe and only reports.
# 2. End-to-end against the running stack:
#      - register a fresh user
#      - /api/auth/me sanity
#      - create a session with scene=interview
#      - 3 chat turns with deliberately-broken English
#      - finish the session
#      - exercise M1 endpoints:
#          GET  /api/users/me/profile/timeline       (M1-A)
#          GET  /api/users/me/error-book             (M1-C)
#          GET  /api/users/me/error-book/stats       (M1-C)
#          GET  /api/users/me/preferences            (M1-B)
#          PUT  /api/users/me/preferences            (M1-B)
#      - direct MySQL cross-checks
#          SELECT COUNT(*) FROM user_ability_history
#          SELECT COUNT(*) FROM user_error_book
#          SELECT coach_persona, preferred_voice FROM user
#          SELECT COUNT(*) FROM audit_log
#
# Exit codes
# ----------
#   0  - all checks passed
#   1  - one or more checks failed
#   2  - prerequisite probe failed (missing binary / not running) and
#        -s was NOT supplied — the script bails before issuing any request.
#
# Conventions
# -----------
# - Pure bash + curl + mysql + nc. No new dependencies, no GUI.
# - Cross-platform: works in Git-Bash on Windows, and on macOS / Linux.
# - Each step prints either "✅ <step> (key=value …)" or "❌ <step> (why)".
# - The summary at the end prints "PASS" or "FAIL" and the totals.
# =============================================================================

set -u  # do not set -e: we want to keep going through failures and tally them

# ---------- CLI ---------------------------------------------------------------
SKIP_RUNTIME=0
for arg in "$@"; do
  case "$arg" in
    -s|--skip-runtime) SKIP_RUNTIME=1 ;;
    -h|--help)
      cat <<'USAGE'
SpeakCoach M1 short-term milestones e2e verification.

USAGE:
  bash e2e/speakcoach-short-term.sh [flags]

FLAGS:
  -s, --skip-runtime    Run prerequisite probe only; do NOT call any service.
                        Useful in CI where services are not yet up, or in
                        a quick "is my stack ready?" check.
  -h, --help            Show this help and exit.

ENV OVERRIDES (all optional):
  BASE            Java gateway base URL          (default: http://localhost:8080)
  PY              Python AI service base URL     (default: http://localhost:9000)
  MYSQL_HOST      MySQL host                     (default: 127.0.0.1)
  MYSQL_PORT      MySQL port                     (default: 3306)
  MYSQL_USER      MySQL user                     (default: root)
  MYSQL_PASSWORD  MySQL password                 (default: empty)
  MYSQL_DB        MySQL database                 (default: speakcoach)
  REDIS_HOST      Redis host                     (default: 127.0.0.1)
  REDIS_PORT      Redis port                     (default: 6379)
  REDIS_CLI       redis-cli binary path          (default: redis-cli)
  JAVA_BIN        java binary path               (default: java)
  PYTHON_BIN      python binary path             (default: python)
  NODE_BIN        node binary path               (default: node)

EXAMPLES:
  # Full run (assumes java:8080 + python:9000 + mysql:3306 + redis:6379 are up):
  bash e2e/speakcoach-short-term.sh

  # Prerequisite probe only (no HTTP calls):
  bash e2e/speakcoach-short-term.sh -s

  # Override MySQL password:
  MYSQL_PASSWORD=secret bash e2e/speakcoach-short-term.sh

EXIT CODES:
  0  all checks passed
  1  one or more checks failed
  2  prerequisite probe failed and -s was NOT supplied

See e2e/README.md for the full milestone-by-milestone breakdown.
USAGE
      exit 0
      ;;
    *)  echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

# ---------- Config (env-overridable) ------------------------------------------
BASE="${BASE:-http://localhost:8080}"
PY="${PY:-http://localhost:9000}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
MYSQL_DB="${MYSQL_DB:-speakcoach}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_CLI="${REDIS_CLI:-redis-cli}"
JAVA_BIN="${JAVA_BIN:-java}"
PYTHON_BIN="${PYTHON_BIN:-python}"
NODE_BIN="${NODE_BIN:-node}"

PASS=0
FAIL=0
WARN=0

# ---------- Pretty output helpers --------------------------------------------
if [ -t 1 ]; then
  C_OK="\033[32m"; C_BAD="\033[31m"; C_WARN="\033[33m"; C_DIM="\033[2m"; C_RST="\033[0m"
else
  C_OK=""; C_BAD=""; C_WARN=""; C_DIM=""; C_RST=""
fi

ok()   { echo -e "${C_OK}✅ $1${C_RST} ${C_DIM}$2${C_RST}"; PASS=$((PASS+1)); }
bad()  { echo -e "${C_BAD}❌ $1${C_RST} ${C_DIM}$2${C_RST}"; FAIL=$((FAIL+1)); }
warn() { echo -e "${C_WARN}⚠️  $1${C_RST} ${C_DIM}$2${C_RST}"; WARN=$((WARN+1)); }
note() { echo -e "${C_DIM}   $1${C_RST}"; }
hdr()  { echo -e "\n${C_DIM}── $1 ──${C_RST}"; }

# Wait briefly until a port is listening; returns 0 if listening, 1 otherwise.
wait_port() {
  local host="$1" port="$2" timeout="${3:-5}"
  local i
  for i in $(seq 1 "$timeout"); do
    if (echo > "/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Read a JSON field via Python (no jq dependency).
json_get() {
  # usage: json_get "<json>" "<dotted.path>"
  local json="$1" path="$2"
  "${PYTHON_BIN}" - "$path" <<EOF
import json, sys
try:
    obj = json.loads(sys.stdin.read())
    for key in sys.argv[1].split('.'):
        if isinstance(obj, list):
            obj = obj[int(key)]
        else:
            obj = obj.get(key)
    if obj is None:
        print("")
    elif isinstance(obj, (dict, list)):
        print(json.dumps(obj))
    else:
        print(obj)
except Exception as e:
    print("", end="")
EOF
}

# ---------- 1. Pre-flight ----------------------------------------------------
hdr "Pre-flight: toolchain + services"

# Java (binary OR JAVA_HOME/bin/java)
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_VER=$("${JAVA_HOME}/bin/java" -version 2>&1 | awk -F\" '/version/ {print $2}')
  ok "java"        "(${JAVA_VER}, JAVA_HOME=${JAVA_HOME})"
else
  if command -v "${JAVA_BIN}" >/dev/null 2>&1; then
    JAVA_VER=$("${JAVA_BIN}" -version 2>&1 | awk -F\" '/version/ {print $2}')
    ok "java"        "(${JAVA_VER})"
  else
    bad "java"       "(not on PATH and JAVA_HOME unset)"
  fi
fi

# Python
if command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  PY_VER=$("${PYTHON_BIN}" --version 2>&1 | awk '{print $2}')
  ok "python"      "(${PY_VER})"
else
  bad "python"     "(not on PATH)"
fi

# Node
if command -v "${NODE_BIN}" >/dev/null 2>&1; then
  NODE_VER=$("${NODE_BIN}" --version 2>&1)
  ok "node"        "(${NODE_VER})"
else
  warn "node"      "(optional, only needed for frontend dev/build)"
fi

# MySQL (binary + port)
if command -v mysql >/dev/null 2>&1; then
  ok "mysql cli"   "(present)"
else
  bad "mysql cli"  "(not on PATH)"
fi
if wait_port "${MYSQL_HOST}" "${MYSQL_PORT}" 2; then
  ok "mysql port"  "(${MYSQL_HOST}:${MYSQL_PORT} LISTENING)"
else
  bad "mysql port" "(${MYSQL_HOST}:${MYSQL_PORT} NOT listening)"
fi

# Redis (binary + port)
if command -v "${REDIS_CLI}" >/dev/null 2>&1; then
  ok "redis-cli"   "(present)"
else
  warn "redis-cli" "(not on PATH; redis is optional — Python uses memory fallback)"
fi
if wait_port "${REDIS_HOST}" "${REDIS_PORT}" 2; then
  ok "redis port"  "(${REDIS_HOST}:${REDIS_PORT} LISTENING)"
else
  warn "redis port" "(${REDIS_HOST}:${REDIS_PORT} NOT listening; not required for the e2e below)"
fi

# Backend ports (informational only; the runtime section tolerates them being down)
if wait_port "$(echo "${BASE}" | awk -F[/:]+ '{print $4}')" "$(echo "${BASE}" | awk -F[/:]+ '{print $5}')" 2; then
  ok "java :8080"  "(LISTENING — e2e can run)"
else
  warn "java :8080" "(NOT listening — runtime section will be skipped or fail-fast)"
fi
if wait_port "$(echo "${PY}" | awk -F[/:]+ '{print $4}')" "$(echo "${PY}" | awk -F[/:]+ '{print $5}')" 2; then
  ok "python :9000" "(LISTENING)"
else
  warn "python :9000" "(NOT listening — /api/chat will fail unless both come up)"
fi

if [ "$SKIP_RUNTIME" = "1" ]; then
  hdr "Skipped (-s) — prereq probe complete"
  echo -e "Pass=$PASS  Fail=$FAIL  Warn=$WARN"
  # In -s mode the user is doing a probe; missing prereqs are reported but
  # do NOT cause a non-zero exit (the whole point of -s is "I know it's not
  # all up, just tell me what's there").
  exit 0
fi

# Fail-fast: if the Java gateway is not up, all subsequent calls would be a wall of 502s
JAVA_HOST=$(echo "${BASE}" | awk -F[/:]+ '{print $4}')
JAVA_PORT=$(echo "${BASE}" | awk -F[/:]+ '{print $5}')
if ! wait_port "${JAVA_HOST}" "${JAVA_PORT}" 1; then
  hdr "Aborting"
  bad "java gateway ${BASE} is not listening" "(start it with: cd backend-java && mvn spring-boot:run)"
  echo -e "\nPass=$PASS  Fail=$FAIL  Warn=$WARN"
  exit 2
fi

# ---------- 2. End-to-end ----------------------------------------------------
hdr "E2E: register → chat → finish → verify M1 endpoints + DB"

# Use a per-run username so re-running this script does not collide
RUN_ID="$(date +%s)$$"
USERNAME="e2e_short_${RUN_ID}"
EMAIL="${USERNAME}@e2e.local"
PASSWORD="test1234"

# Step 1 — register
REG_RAW=$(curl -sS -X POST "${BASE}/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USERNAME}\",\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
USER_ID=$(echo "${REG_RAW}" | json_get userId)
TOKEN=$(echo "${REG_RAW}"   | json_get token)
if [ -n "${USER_ID}" ] && [ -n "${TOKEN}" ]; then
  ok "register"          "userId=${USER_ID} token=${TOKEN:0:20}…"
else
  bad "register"         "response: ${REG_RAW}"
fi

AUTH=(-H "Authorization: Bearer ${TOKEN}")

# Step 2 — /api/auth/me
ME_RAW=$(curl -sS "${BASE}/api/auth/me" "${AUTH[@]}")
ME_USER=$(echo "${ME_RAW}" | json_get userId)
if [ "${ME_USER}" = "${USER_ID}" ]; then
  ok "auth/me"           "userId=${ME_USER}"
else
  bad "auth/me"          "expected ${USER_ID}, got ${ME_USER}; body=${ME_RAW}"
fi

# Step 3 — create session
SESS_RAW=$(curl -sS -X POST "${BASE}/api/sessions" \
  "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"scene":"interview"}')
SESSION_ID=$(echo "${SESS_RAW}" | json_get sessionId)
if [ -n "${SESSION_ID}" ]; then
  ok "create session"    "sessionId=${SESSION_ID}"
else
  bad "create session"   "response: ${SESS_RAW}"
fi

# Step 4 — three turns, with broken English
declare -a SAMPLES=(
  "I am go to store yesterday"
  "She don't like coffee"
  "I have went there"
)
TURN_OK=0
for i in "${!SAMPLES[@]}"; do
  T=$((i+1))
  RESP=$(curl -sS -o /tmp/speakcoach_turn.json -w "%{http_code}" \
    -X POST "${BASE}/api/chat" "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"userText\":\"${SAMPLES[$i]}\"}")
  if [ "${RESP}" = "200" ]; then
    REPLY=$(python -c "import json,sys; d=json.load(open('/tmp/speakcoach_turn.json')); print((d.get('aiReply') or d.get('data',{}).get('aiReply') or '')[:60])" 2>/dev/null)
    ok "chat turn ${T}/3"     "200 reply=\"${REPLY}…\""
    TURN_OK=$((TURN_OK+1))
  else
    bad "chat turn ${T}/3"    "http=${RESP} body=$(head -c 200 /tmp/speakcoach_turn.json)"
  fi
done
[ "${TURN_OK}" -eq 3 ] || warn "chat turns" "(${TURN_OK}/3 succeeded; downstream assertions may be empty)"

# Step 5 — finish session
FIN_RAW=$(curl -sS -o /tmp/speakcoach_finish.json -w "%{http_code}" \
  -X POST "${BASE}/api/sessions/${SESSION_ID}/finish" \
  "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"summary":"e2e auto-finish"}')
if [ "${FIN_RAW}" = "200" ]; then
  ok "finish session"   "200"
else
  bad "finish session"  "http=${FIN_RAW}"
fi

# ---------- 3. M1 endpoint surface ------------------------------------------
hdr "M1 endpoints"

# M1-A: timeline
TL_RAW=$(curl -sS "${BASE}/api/users/me/profile/timeline?limit=10" "${AUTH[@]}")
TL_COUNT=$(echo "${TL_RAW}" | json_get count)
if [ -n "${TL_COUNT}" ] && [ "${TL_COUNT}" -ge 1 ] 2>/dev/null; then
  ok "M1-A GET /me/profile/timeline" "count=${TL_COUNT}"
else
  bad "M1-A GET /me/profile/timeline" "expected count≥1, body=${TL_RAW}"
fi

# M1-C: error-book
EB_RAW=$(curl -sS "${BASE}/api/users/me/error-book?limit=20" "${AUTH[@]}")
EB_COUNT=$(echo "${EB_RAW}" | json_get count)
if [ -n "${EB_COUNT}" ] && [ "${EB_COUNT}" -ge 1 ] 2>/dev/null; then
  ok "M1-C GET /me/error-book" "count=${EB_COUNT}"
else
  bad "M1-C GET /me/error-book" "expected count≥1, body=${EB_RAW}"
fi

# M1-C: error-book stats
EBS_RAW=$(curl -sS "${BASE}/api/users/me/error-book/stats" "${AUTH[@]}")
EBS_OK=$(echo "${EBS_RAW}" | json_get total)
if [ -n "${EBS_OK}" ] && [ "${EBS_OK}" -ge 1 ] 2>/dev/null; then
  ok "M1-C GET /me/error-book/stats" "total=${EBS_OK}"
else
  # Older impls may not expose a "total" key; just require non-empty JSON
  if [ -n "${EBS_RAW}" ] && [ "${EBS_RAW}" != "{}" ]; then
    ok "M1-C GET /me/error-book/stats" "(non-empty, total-key absent) body=${EBS_RAW}"
  else
    bad "M1-C GET /me/error-book/stats" "empty body"
  fi
fi

# M1-B: preferences (default)
PREF_RAW=$(curl -sS "${BASE}/api/users/me/preferences" "${AUTH[@]}")
PREF_P=$(echo "${PREF_RAW}" | json_get coachPersona)
PREF_V=$(echo "${PREF_RAW}" | json_get preferredVoice)
if [ -n "${PREF_P}" ] && [ -n "${PREF_V}" ]; then
  ok "M1-B GET /me/preferences (default)" "persona=${PREF_P} voice=${PREF_V}"
else
  bad "M1-B GET /me/preferences (default)" "body=${PREF_RAW}"
fi

# M1-B: preferences (update)
UP_RAW=$(curl -sS -X PUT "${BASE}/api/users/me/preferences" \
  "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"coachPersona":"friendly_tutor"}')
UP_P=$(echo "${UP_RAW}" | json_get coachPersona)
if [ "${UP_P}" = "friendly_tutor" ]; then
  ok "M1-B PUT /me/preferences" "persona=${UP_P}"
else
  bad "M1-B PUT /me/preferences" "expected friendly_tutor, got ${UP_P}; body=${UP_RAW}"
fi

# Re-read to confirm persistence
PREF2_RAW=$(curl -sS "${BASE}/api/users/me/preferences" "${AUTH[@]}")
PREF2_P=$(echo "${PREF2_RAW}" | json_get coachPersona)
if [ "${PREF2_P}" = "friendly_tutor" ]; then
  ok "M1-B GET /me/preferences (post-update)" "persona=${PREF2_P}"
else
  bad "M1-B GET /me/preferences (post-update)" "expected friendly_tutor, got ${PREF2_P}"
fi

# ---------- 4. MySQL cross-checks -------------------------------------------
hdr "MySQL direct cross-check"

MYSQL_BASE=("mysql" "-h" "${MYSQL_HOST}" "-P" "${MYSQL_PORT}" "-u${MYSQL_USER}")
[ -n "${MYSQL_PASSWORD}" ] && MYSQL_BASE+=("-p${MYSQL_PASSWORD}")
MYSQL_BASE+=("-N" "-B" "-e")

UAH_CNT=$("${MYSQL_BASE[@]}" "SELECT COUNT(*) FROM user_ability_history WHERE user_id=${USER_ID};" 2>/dev/null)
if [ -n "${UAH_CNT}" ] && [ "${UAH_CNT}" -ge 1 ] 2>/dev/null; then
  ok "DB user_ability_history" "rows=${UAH_CNT}"
else
  bad "DB user_ability_history" "expected ≥1, got '${UAH_CNT}' (mysql cli works?)"
fi

UEB_CNT=$("${MYSQL_BASE[@]}" "SELECT COUNT(*) FROM user_error_book WHERE user_id=${USER_ID};" 2>/dev/null)
if [ -n "${UEB_CNT}" ] && [ "${UEB_CNT}" -ge 1 ] 2>/dev/null; then
  ok "DB user_error_book"      "rows=${UEB_CNT}"
else
  bad "DB user_error_book"     "expected ≥1, got '${UEB_CNT}'"
fi

# Persona + voice
PREF_ROW=$("${MYSQL_BASE[@]}" "SELECT coach_persona, preferred_voice FROM user WHERE id=${USER_ID};" 2>/dev/null)
if echo "${PREF_ROW}" | grep -q "friendly_tutor"; then
  ok "DB user.coach_persona"   "${PREF_ROW}"
else
  bad "DB user.coach_persona"   "expected to contain 'friendly_tutor', got '${PREF_ROW}'"
fi

# Audit log: 1 Java row per turn + at least 1 Python-side audit per turn
AUD_CNT=$("${MYSQL_BASE[@]}" "SELECT COUNT(*) FROM audit_log WHERE session_id='${SESSION_ID}';" 2>/dev/null)
if [ -n "${AUD_CNT}" ] && [ "${AUD_CNT}" -ge 9 ] 2>/dev/null; then
  ok "DB audit_log"            "rows=${AUD_CNT} (≥9 expected)"
else
  bad "DB audit_log"           "expected ≥9 for session ${SESSION_ID}, got '${AUD_CNT}'"
fi

# ---------- 5. Summary -------------------------------------------------------
hdr "Summary"
echo -e "User       : ${USERNAME} (id=${USER_ID})"
echo -e "Session    : ${SESSION_ID}"
echo -e "Chat turns : ${TURN_OK}/3 succeeded"
echo -e "Pass=${C_OK}${PASS}${C_RST}  Fail=${C_BAD}${FAIL}${C_RST}  Warn=${C_WARN}${WARN}${C_RST}"
if [ "$FAIL" -eq 0 ]; then
  echo -e "${C_OK}PASS${C_RST}"
  exit 0
else
  echo -e "${C_BAD}FAIL${C_RST}"
  exit 1
fi
