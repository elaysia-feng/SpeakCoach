// ProfilePage —— 长期能力画像 + 趋势页（M1-A）。
// 拉取 /api/users/me/profile + /api/users/me/profile/timeline，
// 把当前画像、4 维 sparkline、最近 N 条快照卡片拼起来。
import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, getAbilityTimeline } from '../lib/api';
import { useAuth } from '../hooks/useAuth';
import Sparkline from '../components/Sparkline';
import type { AbilityHistoryEntry, AbilityProfile } from '../lib/types';

interface RawProfile {
  userId: number;
  grammarScore: number;
  vocabularyScore: number;
  fluencyScore: number;
  logicScore: number;
  commonErrors: string;
}

function parseCommonErrors(raw: string | undefined | null): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.map(String);
  } catch {
    // 不是 JSON 数组，忽略。
  }
  return [];
}

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString();
}

export default function ProfilePage() {
  const { user } = useAuth();
  const [profile, setProfile] = useState<RawProfile | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<AbilityHistoryEntry[]>([]);
  const [timelineError, setTimelineError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      api.get<unknown>('/users/me/profile'),
      getAbilityTimeline(10),
    ])
      .then(([profileRes, history]) => {
        if (cancelled) return;
        const raw = profileRes.data as { data?: AbilityProfile } | AbilityProfile;
        const payload: RawProfile | null =
          raw && typeof raw === 'object' && 'data' in raw && raw.data
            ? (raw.data as unknown as RawProfile)
            : (raw as unknown as RawProfile);
        setProfile(payload);
        setTimeline(history.entries);
      })
      .catch((err) => {
        if (cancelled) return;
        const status = (err as { response?: { status?: number } })?.response?.status;
        const msg =
          (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
        setProfileError(msg || (status ? `Profile load failed (status ${status}).` : 'Profile load failed.'));
        setTimelineError(status ? `History load failed (status ${status}).` : 'History load failed.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const commonErrors = useMemo(() => parseCommonErrors(profile?.commonErrors), [profile]);

  // 把 timeline 顺序反转，让 sparkline 横向 x 轴 = 时间正序（左旧右新）。
  const orderedTimeline = useMemo(
    () => [...timeline].reverse(),
    [timeline],
  );

  return (
    <div className="mx-auto w-full max-w-container px-4 md:px-10 py-12 pb-28 lg:pb-12">
      <header className="mb-10">
        <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
          <div>
            <h1 className="text-3xl font-semibold text-on-surface mb-1">
              Your ability profile
            </h1>
            <p className="text-base leading-7 text-on-surface-variant">
              Track how your grammar, vocabulary, fluency, and logic evolve over time.
              {user ? ` Signed in as ${user.username}.` : ''}
            </p>
          </div>
          <Link
            to="/dashboard"
            className="inline-flex items-center gap-2 text-sm font-semibold text-primary hover:underline"
          >
            <span className="material-symbols-outlined text-[18px]">arrow_back</span>
            Back to dashboard
          </Link>
        </div>
      </header>

      {loading && (
        <p className="text-sm text-on-surface-variant">Loading profile…</p>
      )}

      {profileError && (
        <div
          role="alert"
          className="flex items-start gap-3 mb-6 bg-error-container/40 border border-error/30 text-error rounded-xl px-4 py-3"
        >
          <span className="material-symbols-outlined text-[20px] mt-0.5">error</span>
          <p className="flex-1 text-sm leading-6">{profileError}</p>
        </div>
      )}

      {!loading && profile && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
          <section className="lg:col-span-8 space-y-6">
            <div className="bg-surface-low p-6 rounded-xl border border-outline-variant/30">
              <h2 className="text-2xl font-semibold text-on-surface mb-4">
                Current snapshot
              </h2>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <ScoreCard label="Grammar" score={profile.grammarScore} accent="bg-primary" />
                <ScoreCard label="Vocabulary" score={profile.vocabularyScore} accent="bg-emerald-500" />
                <ScoreCard label="Fluency" score={profile.fluencyScore} accent="bg-amber-500" />
                <ScoreCard label="Logic" score={profile.logicScore} accent="bg-rose-500" />
              </div>
            </div>

            <div className="bg-surface-low p-6 rounded-xl border border-outline-variant/30">
              <h2 className="text-2xl font-semibold text-on-surface mb-2">
                Trend
              </h2>
              <p className="text-sm leading-6 text-on-surface-variant mb-4">
                The sparkline plots every saved snapshot. Use it to spot plateaus and sudden drops.
              </p>
              <Sparkline entries={orderedTimeline} width={720} height={180} className="w-full h-auto" />
              {timelineError && (
                <p className="mt-3 text-sm text-on-surface-variant">{timelineError}</p>
              )}
            </div>

            <div className="bg-surface-low p-6 rounded-xl border border-outline-variant/30">
              <h2 className="text-2xl font-semibold text-on-surface mb-4">
                Recurring mistakes
              </h2>
              {commonErrors.length === 0 ? (
                <p className="text-sm text-on-surface-variant">
                  No recurring mistakes yet — keep practicing to surface patterns.
                </p>
              ) : (
                <ul className="flex flex-wrap gap-2">
                  {commonErrors.map((err, idx) => (
                    <li
                      key={idx}
                      className="text-sm bg-primary-50 text-primary border border-primary/20 rounded-full px-3 py-1"
                    >
                      {err}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>

          <aside className="lg:col-span-4 space-y-6">
            <section className="bg-surface-low p-6 rounded-xl border border-outline-variant/30">
              <h2 className="text-2xl font-semibold text-on-surface mb-4">
                Recent snapshots
              </h2>
              {timeline.length === 0 ? (
                <p className="text-sm text-on-surface-variant">
                  History will appear here after you finish a session.
                </p>
              ) : (
                <ol className="space-y-3">
                  {timeline.slice(0, 10).map((entry) => (
                    <li
                      key={entry.id}
                      className="border border-outline-variant/30 rounded-lg p-3"
                    >
                      <p className="text-sm font-medium text-on-surface">
                        {entry.grammarScore ?? '—'} · {entry.vocabularyScore ?? '—'} · {entry.fluencyScore ?? '—'} · {entry.logicScore ?? '—'}
                      </p>
                      <p className="text-xs text-on-surface-variant mt-1">
                        {formatTimestamp(entry.createdAt)}
                        {entry.sessionId ? ` · session ${entry.sessionId.slice(0, 8)}` : ''}
                      </p>
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </aside>
        </div>
      )}
    </div>
  );
}

function ScoreCard({
  label,
  score,
  accent,
}: {
  label: string;
  score: number | null | undefined;
  accent: string;
}) {
  const safe = typeof score === 'number' ? Math.max(0, Math.min(100, score)) : 0;
  return (
    <div className="bg-white border border-outline-variant/40 rounded-lg p-4">
      <div className="flex items-center gap-2 mb-2">
        <span className={`inline-block w-2 h-2 rounded-full ${accent}`} />
        <span className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant">
          {label}
        </span>
      </div>
      <p className="text-2xl font-semibold text-on-surface">{safe}</p>
      <div className="w-full h-1.5 bg-outline-variant/30 rounded-full mt-2 overflow-hidden">
        <div
          className={`h-full ${accent} rounded-full`}
          style={{ width: `${safe}%` }}
        />
      </div>
    </div>
  );
}
