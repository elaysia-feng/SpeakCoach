// 共享的 TypeScript 类型，对应后端 Java DTO。

export interface AuthResponse {
  userId: number;
  username: string;
  email: string;
  token: string;
}

export interface User {
  userId: number;
  username: string;
  email: string;
}

export interface Scene {
  id: string;
  name: string;
  description: string;
  emoji: string;
}

export interface SessionInfo {
  sessionId: string;
  scene: string;
  createdAt: string;
  /**
   * 课后总结 JSON 字符串（来自 Python 后端，由 finish 流程写入）。
   * 形如 `{"total_turns":..., "next_focus":..., "highlights":[...], ...}`。
   * Java GET 端点目前不一定回带该字段；前端要做空值兜底。
   */
  summary?: string | null;
}

export type CorrectionType = 'grammar' | 'vocab' | 'fluency' | 'logic';
export type CorrectionSeverity = 'low' | 'medium' | 'high';
export type ChatStrategy =
  | 'hint_question'
  | 'normal_follow_up'
  | 'challenge_question'
  | 'review_old_error';

export interface Correction {
  original: string;
  corrected: string;
  type: CorrectionType;
  severity: CorrectionSeverity;
  explanation?: string | null;
}

export interface ChatResponse {
  turnId: number;
  userText?: string | null;
  userAudioUrl?: string | null;
  aiReply: string;
  audioUrl: string;
  corrections: Correction[] | string;
  shadowAnswer: string;
  abilityScore?: AbilityScore | string | null;
  pronunciationScore?: number | string | null;
  strategy: ChatStrategy;
  summary?: SessionSummary | string | null;
  speechMetrics?: SpeechMetrics | string | null;
  wordTimestamps?: WordTimestamp[] | string | null;
}

export interface ChatTurn {
  role: 'user' | 'ai';
  text: string;
  userAudioUrl?: string | null;
  localId?: string;
  pending?: boolean;
  practiceMode?: 'shadowing';
  practiceTarget?: string;
  response?: ChatResponse;
}

export interface AbilityScore {
  grammar: number;
  vocabulary: number;
  fluency: number;
  logic: number;
  pronunciation?: number;
}

/** M1-A 兼容别名：旧 ProfilePage 引用了 AbilityProfile，等价于 AbilityScore。 */
export type AbilityProfile = AbilityScore;

export interface SessionSummary {
  total_turns?: number;
  error_counts_by_type?: Record<string, number>;
  ability_score_delta?: Partial<AbilityScore>;
  next_focus?: string;
  highlights?: string[];
}

export interface SpeechMetrics {
  source?: string;
  practice_mode?: string;
  practice_target?: string;
  shadow_similarity_score?: number;
  word_count?: number;
  duration_seconds?: number;
  words_per_minute?: number;
  pause_count?: number;
  pauses?: Array<{ after?: string; before?: string; duration?: number }>;
  completeness?: number;
  fluency_score?: number;
  pronunciation_score?: number;
}

export interface WordTimestamp {
  word: string;
  start: number;
  end: number;
}

export interface ChatHistoryItem {
  turnId: number;
  userText: string;
  userAudioUrl?: string | null;
  aiReply: string;
  audioUrl: string;
  corrections: Correction[] | string;
  shadowAnswer: string;
  abilityScore?: AbilityScore | string | null;
  pronunciationScore?: number | string | null;
  strategy: ChatStrategy;
  summary?: SessionSummary | string | null;
  speechMetrics?: SpeechMetrics | string | null;
  wordTimestamps?: WordTimestamp[] | string | null;
}

export interface ChatHistoryResponse {
  sessionId: string;
  turns: ChatHistoryItem[];
}

// =====================================================================
// M1-B: 教练人格 + 音色偏好
// =====================================================================

/** 4 种教练人格，与后端 `UserPreferencesService.VALID_PERSONAS` 一致。 */
export type CoachPersona = 'warm_strict' | 'friendly_tutor' | 'ielts_examiner' | 'patient_grandma';

/** 当前唯一支持的 TTS 音色；预留扩展。 */
export type VoiceId = 'linqian_voice';

export interface UserPreferences {
  coachPersona: CoachPersona;
  preferredVoice: VoiceId;
}

/** PUT /api/users/me/preferences 请求体 —— 任一字段可选（null = 保留原值）。 */
export interface UserPreferencesUpdate {
  coachPersona?: CoachPersona | null;
  preferredVoice?: VoiceId | null;
}

// ============================================================================
// M1-C 错题本
// ============================================================================

export type ErrorBookMastery = 0 | 1 | 2 | 3;
export type ErrorBookType = 'grammar' | 'vocab' | 'fluency' | 'logic';

export interface ErrorBookEntry {
  id: number;
  type: ErrorBookType;
  original: string;
  corrected: string;
  explanation?: string | null;
  sourceSessionId?: string | null;
  sourceTurnId?: number | null;
  masteryLevel: ErrorBookMastery;
  nextReviewAt?: string | null;
  reviewCount: number;
  createdAt?: string | null;
}

export interface ErrorBookListResponse {
  entries: ErrorBookEntry[];
}

export interface ErrorBookStats {
  byType: Record<string, number>;
}

// 会话报告页用的聚合视图（从 session + turns + errorBook 自己拼出来）。
export interface SessionReport {
  sessionId: string;
  scene: string;
  status?: string | null;
  turnCount: number;
  averageAbility: AbilityScore;
  topErrors: ErrorBookEntry[];
  errorDistribution: Array<{ type: string; count: number }>;
}

// ============================================================================
// M1-A 长期记忆：能力历史快照
// ============================================================================

/** 来自 /api/users/me/profile/timeline 的单条历史快照。 */
export interface AbilityHistoryEntry {
  id: number;
  sessionId?: string | null;
  turnId?: number | null;
  grammarScore?: number | null;
  vocabularyScore?: number | null;
  fluencyScore?: number | null;
  logicScore?: number | null;
  commonErrors?: string | null;
  cefrLevel?: string | null;
  snapshotJson?: string | null;
  createdAt?: string | null;
}

/** /api/users/me/profile/timeline 响应体。 */
export interface AbilityHistoryResponse {
  userId: number;
  count: number;
  entries: AbilityHistoryEntry[];
}
