// ReportPage —— 报告页（/report/:sessionId）。
// 视觉参考：front/stitch/practice_report_speakcoach_agent/code.html
// 加载：session + turns + ability score，再补 errorBook 拉 Top 5。

import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import AbilityRadar from '../components/AbilityRadar';
import ErrorCard from '../components/ErrorCard';
import { api, getErrorBook } from '../lib/api';
import type {
  AbilityScore,
  ChatHistoryItem,
  ChatHistoryResponse,
  ErrorBookEntry,
  SessionInfo,
  SessionSummary,
} from '../lib/types';

const SCENE_NAME: Record<string, string> = {
  interview: 'Job Interview',
  travel: 'Travel & Tourism',
  daily_chat: 'Daily Conversation',
  business: 'Business English',
  free: 'Free Talk',
};

const TYPE_COLORS: Record<string, string> = {
  grammar: '#004ac6',
  vocab: '#712ae2',
  fluency: '#006242',
  logic: '#ba1a1a',
};

const TYPE_LABELS: Record<string, string> = {
  grammar: 'Grammar',
  vocab: 'Vocab',
  fluency: 'Fluency',
  logic: 'Logic',
};

export default function ReportPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();

  const [session, setSession] = useState<SessionInfo | null>(null);
  const [turns, setTurns] = useState<ChatHistoryItem[]>([]);
  const [errorBook, setErrorBook] = useState<ErrorBookEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);

    Promise.all([
      api.get<SessionInfo>(`/sessions/${sessionId}`).then((r) => r.data),
      api
        .get<ChatHistoryResponse>('/chat/history', { params: { sessionId } })
        .then((r) => r.data)
        .catch(() => ({ sessionId, turns: [] })),
      getErrorBook({ limit: 200 }).catch(() => []),
    ])
      .then(([sessionData, history, allErrors]) => {
        if (cancelled) return;
        setSession(sessionData);
        setTurns(Array.isArray(history?.turns) ? history.turns : []);
        // 只保留来源匹配本会话的错题；如果 sourceSessionId 缺失则全部纳入。
        const sessionErrors = (allErrors as ErrorBookEntry[]).filter(
          (e) => !sessionId || !e.sourceSessionId || e.sourceSessionId === sessionId,
        );
        setErrorBook(sessionErrors);
      })
      .catch((err) => {
        if (cancelled) return;
        const status = (err as any)?.response?.status;
        const msg = (err as any)?.response?.data?.message;
        setError(msg || (status ? `Could not load report (status ${status}).` : 'Could not load report.'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  // AC-U3: ability score = first turn vs last turn delta (per axis).
  // - 0 turns: all-zero baseline with "No data" placeholder downstream.
  // - 1 turn:  no delta possible → show that single score as-is.
  // - ≥2 turns: delta = last - first.
  const abilityDelta = useMemo<AbilityScore>(() => abilityDeltaFromTurns(turns), [turns]);
  const abilitySampleCount = useMemo(
    () => turns.filter((t) => parseAbility(t.abilityScore) !== null).length,
    [turns],
  );
  const totalCorrections = useMemo(
    () => turns.reduce((sum, t) => sum + countCorrections(t.corrections), 0),
    [turns],
  );
  const errorDistribution = useMemo(
    () => buildDistribution(turns),
    [turns],
  );
  // AC-U3: prefer backend SessionSummary.next_focus; fall back to client heuristic
  // only when the field is missing or empty.
  const backendSummary = useMemo<SessionSummary | null>(
    () => parseSummaryFromSession(session),
    [session],
  );
  const nextFocus = useMemo(
    () => backendSummary?.next_focus?.trim() || coachTip(abilityDelta, errorDistribution, abilitySampleCount),
    [backendSummary, abilityDelta, errorDistribution, abilitySampleCount],
  );
  const topErrors = useMemo(() => {
    // 优先用本会话关联的错题；不足 5 条时用所有错题补齐。
    const byMastery = [...errorBook].sort((a, b) => a.masteryLevel - b.masteryLevel);
    return byMastery.slice(0, 5);
  }, [errorBook]);

  const scene = session?.scene ?? 'interview';
  const sceneName = SCENE_NAME[scene] ?? scene;

  return (
    <div className="mx-auto w-full max-w-container px-4 md:px-10 py-10 pb-28 space-y-8">
      <header className="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant">
            Practice Report
          </p>
          <h1 className="font-headline-md text-headline-md text-on-surface mt-1">
            {sceneName} <span className="text-primary">·</span> Session Recap
          </h1>
          <p className="text-sm text-on-surface-variant mt-1">
            {session
              ? `${turns.length} turn${turns.length === 1 ? '' : 's'} · ${totalCorrections} correction${
                  totalCorrections === 1 ? '' : 's'
                } captured`
              : 'Loading session...'}
          </p>
        </div>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => navigate('/error-book')}
            className="inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold border border-outline-variant hover:bg-surface-container-low transition-colors"
          >
            <span className="material-symbols-outlined text-[18px]">menu_book</span>
            View Error Book
          </button>
          <button
            type="button"
            onClick={() => navigate('/dashboard')}
            className="inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold bg-primary text-on-primary hover:bg-primary-container transition-colors"
          >
            <span className="material-symbols-outlined text-[18px]">home</span>
            Dashboard
          </button>
        </div>
      </header>

      {error && (
        <div className="bg-error-container/30 border border-error/30 text-error rounded-xl px-4 py-3 text-sm">
          {error}
        </div>
      )}

      {loading && !session ? (
        <Skeleton />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* 能力雷达：AC-U3 delta = last − first；radar 把 delta 线性映射到 [0,100]（中心 50，对应无变化）方便可视化。 */}
          <section className="lg:col-span-5 bg-white border border-outline-variant/30 rounded-2xl p-6 shadow-sm">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-bold">Ability Delta</h2>
              <span className="text-xs font-semibold text-on-surface-variant">
                {turns.length === 0
                  ? 'No data'
                  : turns.length === 1
                    ? '1 turn (no delta)'
                    : `first vs last of ${turns.length} turns`}
              </span>
            </div>
            <div className="flex justify-center">
              <AbilityRadar
                values={{
                  grammar: deltaToRadar(abilityDelta.grammar),
                  vocabulary: deltaToRadar(abilityDelta.vocabulary),
                  fluency: deltaToRadar(abilityDelta.fluency),
                  logic: deltaToRadar(abilityDelta.logic),
                }}
                size={280}
              />
            </div>
            <p className="text-xs text-on-surface-variant mt-4 text-center">
              {turns.length <= 1
                ? 'Single-turn score — add more turns to see a session delta.'
                : 'Per-axis delta = last turn score − first turn score. Center of the radar = no change.'}
            </p>
          </section>

          {/* 错误分布饼图 */}
          <section className="lg:col-span-4 bg-white border border-outline-variant/30 rounded-2xl p-6 shadow-sm">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-bold">Error Distribution</h2>
              <span className="text-xs font-semibold text-on-surface-variant">{totalCorrections} total</span>
            </div>
            {totalCorrections === 0 ? (
              <p className="text-sm text-on-surface-variant">No corrections were captured for this session. Great job!</p>
            ) : (
              <div className="flex flex-col items-center gap-4">
                <DonutChart data={errorDistribution} />
                <ul className="w-full space-y-1.5">
                  {errorDistribution.map((item) => (
                    <li key={item.type} className="flex items-center justify-between text-sm">
                      <span className="flex items-center gap-2">
                        <span
                          className="inline-block w-2.5 h-2.5 rounded-full"
                          style={{ background: TYPE_COLORS[item.type] ?? '#737686' }}
                        />
                        {TYPE_LABELS[item.type] ?? item.type}
                      </span>
                      <span className="font-semibold">{item.count}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </section>

          {/* 能力总分 + 建议 */}
          <section className="lg:col-span-3 bg-surface-container-low border border-outline-variant/20 rounded-2xl p-6 shadow-sm space-y-4">
            <h2 className="text-lg font-bold">Highlights</h2>
            <Stat label={turns.length <= 1 ? 'Grammar' : 'Δ Grammar'} value={abilityDelta.grammar ?? 0} />
            <Stat label={turns.length <= 1 ? 'Vocabulary' : 'Δ Vocabulary'} value={abilityDelta.vocabulary ?? 0} />
            <Stat label={turns.length <= 1 ? 'Fluency' : 'Δ Fluency'} value={abilityDelta.fluency ?? 0} />
            <Stat label={turns.length <= 1 ? 'Logic' : 'Δ Logic'} value={abilityDelta.logic ?? 0} />
            <div className="pt-2 border-t border-outline-variant/30">
              <p className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant mb-1">
                {backendSummary?.next_focus ? 'Next Focus' : 'Coach Tip'}
              </p>
              <p className="text-sm text-on-surface leading-6">{nextFocus}</p>
            </div>
          </section>

          {/* Top 5 错句 */}
          <section className="lg:col-span-12 bg-white border border-outline-variant/30 rounded-2xl p-6 shadow-sm">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-bold">Top 5 Errors</h2>
              <button
                type="button"
                onClick={() => navigate('/error-book')}
                className="text-xs font-semibold text-primary hover:underline"
              >
                See all →
              </button>
            </div>
            {topErrors.length === 0 ? (
              <p className="text-sm text-on-surface-variant">No errors recorded for this session.</p>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                {topErrors.map((entry) => (
                  <ErrorCard key={entry.id} entry={entry} compact />
                ))}
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

function abilityDeltaFromTurns(turns: ChatHistoryItem[]): AbilityScore {
  const abilities = turns
    .map((turn) => parseAbility(turn.abilityScore))
    .filter((value): value is AbilityScore => value !== null);
  if (abilities.length === 0) {
    return { grammar: 0, vocabulary: 0, fluency: 0, logic: 0 };
  }
  if (abilities.length === 1) {
    const only = abilities[0];
    return {
      grammar: only.grammar ?? 0,
      vocabulary: only.vocabulary ?? 0,
      fluency: only.fluency ?? 0,
      logic: only.logic ?? 0,
    };
  }
  const first = abilities[0];
  const last = abilities[abilities.length - 1];
  return {
    grammar: (last.grammar ?? 0) - (first.grammar ?? 0),
    vocabulary: (last.vocabulary ?? 0) - (first.vocabulary ?? 0),
    fluency: (last.fluency ?? 0) - (first.fluency ?? 0),
    logic: (last.logic ?? 0) - (first.logic ?? 0),
  };
}

function parseSummaryFromSession(session: SessionInfo | null): SessionSummary | null {
  if (!session?.summary) return null;
  const raw = session.summary;
  if (typeof raw !== 'string') return raw as SessionSummary;
  try {
    return JSON.parse(raw) as SessionSummary;
  } catch {
    return null;
  }
}

/** 把 -50..+50 的 delta 映射到雷达的 0..100（中心 50）；超出范围按 ±50 截断。 */
function deltaToRadar(delta: number | undefined): number {
  if (delta === undefined || delta === null || Number.isNaN(delta)) return 50;
  return Math.max(0, Math.min(100, 50 + delta * 2));
}

function parseAbility(value: ChatHistoryItem['abilityScore']): AbilityScore | null {
  if (!value) return null;
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as AbilityScore;
    } catch {
      return null;
    }
  }
  return value;
}

function countCorrections(value: ChatHistoryItem['corrections']): number {
  if (!value) return 0;
  if (Array.isArray(value)) return value.length;
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.length : 0;
  } catch {
    return 0;
  }
}

function buildDistribution(turns: ChatHistoryItem[]): Array<{ type: string; count: number }> {
  const counts: Record<string, number> = { grammar: 0, vocab: 0, fluency: 0, logic: 0 };
  for (const turn of turns) {
    const list = parseCorrections(turn.corrections);
    for (const c of list) {
      const t = c.type ?? 'grammar';
      counts[t] = (counts[t] ?? 0) + 1;
    }
  }
  return Object.entries(counts)
    .filter(([, n]) => n > 0)
    .map(([type, count]) => ({ type, count }));
}

function parseCorrections(value: ChatHistoryItem['corrections']): Array<{ type?: string }> {
  if (!value) return [];
  if (Array.isArray(value)) return value as Array<{ type?: string }>;
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? (parsed as Array<{ type?: string }>) : [];
  } catch {
    return [];
  }
}

function coachTip(
  ability: AbilityScore,
  distribution: Array<{ type: string; count: number }>,
  sampleCount: number,
): string {
  if (sampleCount === 0) {
    return 'No data yet — start a session to get personalized recommendations.';
  }
  const weakest = [
    { key: 'Grammar', value: ability.grammar ?? 0 },
    { key: 'Vocabulary', value: ability.vocabulary ?? 0 },
    { key: 'Fluency', value: ability.fluency ?? 0 },
    { key: 'Logic', value: ability.logic ?? 0 },
  ].sort((a, b) => a.value - b.value)[0];
  if (!weakest) return 'Keep practicing!';
  const topType = [...distribution].sort((a, b) => b.count - a.count)[0];
  if (topType) {
    const label = TYPE_LABELS[topType.type] ?? topType.type;
    return `Focus on ${label.toLowerCase()} next session — ${topType.count} ${label.toLowerCase()} correction${
      topType.count === 1 ? '' : 's'
    } captured. Your ${weakest.key.toLowerCase()} score is your weakest area (${weakest.value}).`;
  }
  return `Your ${weakest.key.toLowerCase()} score is your weakest area (${weakest.value}). Aim for short daily drills.`;
}

function Stat({ label, value }: { label: string; value: number }) {
  // Deltas can be negative; show the signed number and only clamp the bar's
  // visual width into [0, 100] for display purposes.
  const v = Math.round(value || 0);
  const barWidth = Math.max(0, Math.min(100, Math.abs(v) * 2));
  const isDelta = label.startsWith('Δ');
  return (
    <div>
      <div className="flex justify-between items-center mb-1">
        <span className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant">
          {label}
        </span>
        <span
          className={`text-sm font-bold ${
            isDelta && v > 0
              ? 'text-tertiary'
              : isDelta && v < 0
                ? 'text-error'
                : 'text-on-surface'
          }`}
        >
          {isDelta && v > 0 ? `+${v}` : v}
        </span>
      </div>
      <div className="h-1.5 bg-outline-variant/20 rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full ${
            isDelta && v < 0 ? 'bg-error' : 'bg-primary'
          }`}
          style={{ width: `${barWidth}%` }}
        />
      </div>
    </div>
  );
}

function Skeleton() {
  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
      {[3, 2, 2, 4].map((span, idx) => (
        <div
          key={idx}
          className={`lg:col-span-${span} bg-surface-container-lowest border border-outline-variant/20 rounded-2xl p-6 h-64 animate-pulse`}
        />
      ))}
    </div>
  );
}

function DonutChart({ data }: { data: Array<{ type: string; count: number }> }) {
  const size = 160;
  const cx = size / 2;
  const cy = size / 2;
  const r = 60;
  const inner = 38;
  const total = data.reduce((sum, item) => sum + item.count, 0);
  if (total === 0) return null;
  let cumulative = 0;
  const segments = data.map((item) => {
    const start = cumulative / total;
    cumulative += item.count;
    const end = cumulative / total;
    return { ...item, start, end };
  });
  function point(angle: number) {
    return { x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r };
  }
  function pointInner(angle: number) {
    return { x: cx + Math.cos(angle) * inner, y: cy + Math.sin(angle) * inner };
  }
  return (
    <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size} role="img" aria-label="Error distribution">
      {segments.map((seg) => {
        const startAngle = seg.start * 2 * Math.PI - Math.PI / 2;
        const endAngle = seg.end * 2 * Math.PI - Math.PI / 2;
        const largeArc = seg.end - seg.start > 0.5 ? 1 : 0;
        const p1 = point(startAngle);
        const p2 = point(endAngle);
        const p3 = pointInner(endAngle);
        const p4 = pointInner(startAngle);
        const d = [
          `M ${p1.x} ${p1.y}`,
          `A ${r} ${r} 0 ${largeArc} 1 ${p2.x} ${p2.y}`,
          `L ${p3.x} ${p3.y}`,
          `A ${inner} ${inner} 0 ${largeArc} 0 ${p4.x} ${p4.y}`,
          'Z',
        ].join(' ');
        return (
          <path
            key={seg.type}
            d={d}
            fill={TYPE_COLORS[seg.type] ?? '#737686'}
            stroke="#ffffff"
            strokeWidth={1.5}
          />
        );
      })}
      <text
        x={cx}
        y={cy - 6}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize={22}
        fontWeight={700}
        fill="#0b1c30"
      >
        {total}
      </text>
      <text
        x={cx}
        y={cy + 14}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize={10}
        fontWeight={600}
        fill="#434655"
      >
        total
      </text>
    </svg>
  );
}
