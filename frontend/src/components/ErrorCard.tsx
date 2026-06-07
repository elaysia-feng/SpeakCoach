// ErrorCard —— 单条错题卡片。

import { useState } from 'react';
import type { ErrorBookEntry } from '../lib/types';

interface ErrorCardProps {
  entry: ErrorBookEntry;
  onMarkMastered?: (id: number) => Promise<boolean> | boolean;
  compact?: boolean;
}

const TYPE_LABEL: Record<string, string> = {
  grammar: 'Grammar',
  vocab: 'Vocab',
  fluency: 'Fluency',
  logic: 'Logic',
};

const TYPE_TONE: Record<string, string> = {
  grammar: 'bg-primary/10 text-primary',
  vocab: 'bg-secondary/10 text-secondary',
  fluency: 'bg-tertiary/10 text-tertiary',
  logic: 'bg-error/10 text-error',
};

export default function ErrorCard({ entry, onMarkMastered, compact = false }: ErrorCardProps) {
  const [busy, setBusy] = useState(false);
  const isMastered = entry.masteryLevel >= 3;

  const handleClick = async () => {
    if (!onMarkMastered || busy || isMastered) return;
    setBusy(true);
    try {
      await onMarkMastered(entry.id);
    } finally {
      setBusy(false);
    }
  };

  return (
    <article
      className={`bg-white border border-outline-variant/30 rounded-2xl p-4 shadow-sm ${
        isMastered ? 'opacity-60' : ''
      } ${compact ? '' : 'hover:shadow-md transition-shadow'}`}
    >
      <div className="flex items-center justify-between gap-2 mb-2">
        <span className={`text-[11px] font-semibold uppercase tracking-wider rounded-md px-2 py-0.5 ${TYPE_TONE[entry.type] ?? 'bg-surface-container text-on-surface'}`}>
          {TYPE_LABEL[entry.type] ?? entry.type}
        </span>
        <span className="text-[10px] font-semibold uppercase tracking-wider text-on-surface-variant">
          {isMastered ? `Mastered · ${entry.reviewCount}` : `New · ${entry.reviewCount}`}
        </span>
      </div>
      <p className="text-sm leading-6 text-on-surface">
        <span className="line-through decoration-2 decoration-error/70 text-error/80 mr-1.5">
          {entry.original}
        </span>
        <span className="text-on-surface-variant mx-1" aria-hidden>→</span>
        <span className="text-tertiary bg-tertiary-400/10 px-1 rounded">{entry.corrected}</span>
      </p>
      {entry.explanation && (
        <p className="mt-2 text-xs leading-5 text-on-surface-variant italic">{entry.explanation}</p>
      )}
      {onMarkMastered && (
        <div className="mt-3 flex justify-end">
          <button
            type="button"
            onClick={handleClick}
            disabled={busy || isMastered}
            className={`text-xs font-semibold inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 transition-colors ${
              isMastered
                ? 'bg-surface-container text-on-surface-variant cursor-default'
                : 'bg-primary/10 text-primary hover:bg-primary/20 disabled:opacity-50'
            }`}
          >
            <span className="material-symbols-outlined text-[16px]">
              {isMastered ? 'check_circle' : 'task_alt'}
            </span>
            {isMastered ? 'Mastered' : busy ? 'Marking...' : 'Mark Mastered'}
          </button>
        </div>
      )}
    </article>
  );
}
