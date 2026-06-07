// ErrorBookPage —— 错题本浏览页（/error-book）。
// 拉取 /api/users/me/error-book，渲染卡片网格。
// 点 "Mark Mastered" → POST /master，从列表中立即移除或切换到已掌握视图。

import { useCallback, useEffect, useMemo, useState } from 'react';
import ErrorCard from '../components/ErrorCard';
import { getErrorBook, markErrorMastered } from '../lib/api';
import type { ErrorBookEntry, ErrorBookType } from '../lib/types';

const TYPES: Array<{ value: '' | ErrorBookType; label: string }> = [
  { value: '', label: 'All' },
  { value: 'grammar', label: 'Grammar' },
  { value: 'vocab', label: 'Vocab' },
  { value: 'fluency', label: 'Fluency' },
  { value: 'logic', label: 'Logic' },
];

const MASTERY_VIEWS: Array<{ value: '' | '0' | '3'; label: string }> = [
  { value: '', label: 'All' },
  { value: '0', label: 'New' },
  { value: '3', label: 'Mastered' },
];

export default function ErrorBookPage() {
  const [entries, setEntries] = useState<ErrorBookEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [type, setType] = useState<'' | ErrorBookType>('');
  const [mastery, setMastery] = useState<'' | '0' | '3'>('');
  const [hiding, setHiding] = useState<Set<number>>(new Set());

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getErrorBook({
        type: type || undefined,
        mastery: mastery === '' ? undefined : Number(mastery),
        limit: 200,
      });
      setEntries(data);
    } catch (err) {
      const status = (err as any)?.response?.status;
      const msg = (err as any)?.response?.data?.message;
      setError(msg || (status ? `Could not load error book (status ${status}).` : 'Could not load error book.'));
    } finally {
      setLoading(false);
    }
  }, [type, mastery]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleMarkMastered = useCallback(
    async (id: number) => {
      const ok = await markErrorMastered(id);
      if (ok) {
        // 标记为已掌握 —— 默认从当前视图移除（"New" 视图），否则更新 masteryLevel。
        setEntries((prev) =>
          prev.map((e) => (e.id === id ? { ...e, masteryLevel: 3 as const, reviewCount: e.reviewCount + 1 } : e)),
        );
        if (mastery === '0') {
          setHiding((prev) => new Set(prev).add(id));
          setEntries((prev) => prev.filter((e) => e.id !== id));
        }
      }
      return ok;
    },
    [mastery],
  );

  const visible = useMemo(() => entries.filter((e) => !hiding.has(e.id)), [entries, hiding]);

  return (
    <div className="mx-auto w-full max-w-container px-4 md:px-10 py-10 pb-28 space-y-6">
      <header className="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant">
            My Error Book
          </p>
          <h1 className="font-headline-md text-headline-md text-on-surface mt-1">Error Book</h1>
          <p className="text-sm text-on-surface-variant mt-1">
            Review the mistakes you made and mark the ones you have mastered.
          </p>
        </div>
      </header>

      <div className="flex flex-wrap items-center gap-2">
        <Filter label="Type" value={type} options={TYPES} onChange={(v) => setType(v as '' | ErrorBookType)} />
        <Filter label="Mastery" value={mastery} options={MASTERY_VIEWS} onChange={(v) => setMastery(v as '' | '0' | '3')} />
        <button
          type="button"
          onClick={() => {
            setType('');
            setMastery('');
          }}
          className="ml-auto text-xs font-semibold text-on-surface-variant hover:text-primary"
        >
          Reset
        </button>
      </div>

      {error && (
        <div className="bg-error-container/30 border border-error/30 text-error rounded-xl px-4 py-3 text-sm">
          {error}
        </div>
      )}

      {loading && visible.length === 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-32 rounded-2xl bg-surface-container-lowest border border-outline-variant/20 animate-pulse" />
          ))}
        </div>
      ) : visible.length === 0 ? (
        <div className="bg-white border border-outline-variant/30 rounded-2xl p-10 text-center">
          <span className="material-symbols-outlined text-[40px] text-primary">auto_awesome</span>
          <p className="text-base font-semibold text-on-surface mt-3">No errors yet</p>
          <p className="text-sm text-on-surface-variant mt-1">
            Complete a practice session to start collecting mistakes here.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {visible.map((entry) => (
            <ErrorCard key={entry.id} entry={entry} onMarkMastered={handleMarkMastered} />
          ))}
        </div>
      )}
    </div>
  );
}

function Filter<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: Array<{ value: T; label: string }>;
  onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex items-center gap-1 bg-white border border-outline-variant/40 rounded-lg p-1">
      <span className="px-2 text-[11px] font-semibold uppercase tracking-wider text-on-surface-variant">
        {label}
      </span>
      {options.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={String(opt.value)}
            type="button"
            onClick={() => onChange(opt.value)}
            className={`text-xs font-semibold rounded-md px-2.5 py-1 transition-colors ${
              active ? 'bg-primary text-on-primary' : 'text-on-surface-variant hover:bg-surface-container-low'
            }`}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}
