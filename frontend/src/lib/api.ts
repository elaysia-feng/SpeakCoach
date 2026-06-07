import axios, { type AxiosInstance } from 'axios';
import { getToken, clearAuth } from './auth';
import type {
  AbilityHistoryResponse,
  ErrorBookEntry,
  ErrorBookListResponse,
  ErrorBookStats,
  UserPreferences,
  UserPreferencesUpdate,
} from './types';

// 基础 URL：优先使用环境变量；否则使用 Vite 开发服务器上 /api 的代理
const baseURL = import.meta.env.VITE_API_BASE || '/api';

// 用于记住用户被踢回 /login 时所在路径的键名。
const REDIRECT_KEY = 'speakcoach.redirect';

// 共享的 axios 实例 —— 自动附加 JWT，并在收到 401 时将用户踢回 /login。
export const api: AxiosInstance = axios.create({
  baseURL,
  // 150s 客户端超时：与 Java application.yml 的 python.service.timeout-seconds=150
  // 保持一致。LangGraph 一次 turn 串行 5 次 LLM + TTS 在 MiniMax API 慢时可超 60s，
  // 30s 太短会产生假 504。Java 端 150s timeout 是真实上限,客户端 150s
  // 略早一点触发,避免被 netty 关连接产生 noisy stack trace。
  timeout: 150_000,
});

api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (error) => {
    if (error?.response?.status === 401) {
      clearAuth();
      // 避免在已经处于 /login 时覆盖 URL
      if (window.location.pathname !== '/login') {
        try {
          sessionStorage.setItem(REDIRECT_KEY, window.location.pathname + window.location.search);
        } catch {
          // sessionStorage 可能不可用（隐私模式）；静默跳过。
        }
        window.location.assign('/login');
      }
    }
    return Promise.reject(error);
  },
);

export function consumePostLoginRedirect(fallback: string): string {
  try {
    const saved = sessionStorage.getItem(REDIRECT_KEY);
    if (saved) {
      sessionStorage.removeItem(REDIRECT_KEY);
      return saved;
    }
  } catch {
    // sessionStorage 可能不可用；落到默认值。
  }
  return fallback;
}

// ============================================================================
// M1-C 错题本 API
// ============================================================================

export interface ErrorBookQuery {
  mastery?: number;
  type?: string;
  limit?: number;
}

function unwrap<T>(payload: unknown): T {
  if (payload && typeof payload === 'object' && 'data' in (payload as Record<string, unknown>)) {
    return (payload as { data: T }).data;
  }
  return payload as T;
}

/** GET /api/users/me/error-book —— 列出当前用户的错题。 */
export async function getErrorBook(query: ErrorBookQuery = {}): Promise<ErrorBookEntry[]> {
  const params: Record<string, string | number> = {};
  if (query.mastery !== undefined) params.mastery = query.mastery;
  if (query.type) params.type = query.type;
  if (query.limit !== undefined) params.limit = query.limit;
  const res = await api.get<unknown>('/users/me/error-book', { params });
  const data = unwrap<ErrorBookListResponse>(res.data);
  return Array.isArray(data?.entries) ? data.entries : [];
}

/** POST /api/users/me/error-book/{id}/master —— 标记已掌握。 */
export async function markErrorMastered(id: number): Promise<boolean> {
  const res = await api.post<unknown>(`/users/me/error-book/${id}/master`);
  const data = unwrap<{ ok: boolean }>(res.data);
  return Boolean(data?.ok);
}

/** GET /api/users/me/error-book/stats —— 最近 7 天按 type 聚合的新增数。 */
export async function getErrorBookStats(): Promise<ErrorBookStats> {
  const res = await api.get<unknown>('/users/me/error-book/stats');
  const data = unwrap<ErrorBookStats>(res.data);
  return { byType: data?.byType ?? {} };
}

// ============================================================================
// M1-B 用户偏好（教练人格 + 音色）
// ============================================================================

/** GET /api/users/me/preferences —— 拉取当前用户的偏好。 */
export async function getPreferences(): Promise<UserPreferences> {
  const res = await api.get<unknown>('/users/me/preferences');
  const data = unwrap<UserPreferences>(res.data);
  // 后端字段名是 camelCase（coachPersona / preferredVoice），保持一致。
  return {
    coachPersona: data?.coachPersona ?? 'warm_strict',
    preferredVoice: data?.preferredVoice ?? 'linqian_voice',
  };
}

/** PUT /api/users/me/preferences —— 部分更新当前用户的偏好。 */
export async function updatePreferences(
  payload: UserPreferencesUpdate,
): Promise<UserPreferences> {
  const body: Record<string, string | null> = {};
  if (payload.coachPersona !== undefined) {
    body.coachPersona = payload.coachPersona;
  }
  if (payload.preferredVoice !== undefined) {
    body.preferredVoice = payload.preferredVoice;
  }
  const res = await api.put<unknown>('/users/me/preferences', body);
  const data = unwrap<UserPreferences>(res.data);
  return {
    coachPersona: data?.coachPersona ?? 'warm_strict',
    preferredVoice: data?.preferredVoice ?? 'linqian_voice',
  };
}

// ============================================================================
// M1-A 能力历史 API
// ============================================================================

/** GET /api/users/me/profile/timeline —— 拉取最近 N 条能力快照。 */
export async function getAbilityTimeline(limit: number = 10): Promise<AbilityHistoryResponse> {
  const safeLimit = Math.max(1, Math.min(100, limit));
  const res = await api.get<unknown>('/users/me/profile/timeline', {
    params: { limit: safeLimit },
  });
  const data = unwrap<AbilityHistoryResponse>(res.data);
  return {
    userId: data?.userId ?? 0,
    count: data?.count ?? 0,
    entries: Array.isArray(data?.entries) ? data.entries : [],
  };
}
