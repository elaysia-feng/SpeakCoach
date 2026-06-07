import type { Correction } from '../lib/types';

interface CorrectionsPanelProps {
  corrections: Correction[];
}

export default function CorrectionsPanel({ corrections }: CorrectionsPanelProps) {
  return (
    <section className="bg-white border border-outline-variant/20 rounded-xl p-4 shadow-sm">
      <div className="flex items-center gap-2 mb-3 text-error">
        <span className="material-symbols-outlined text-[20px]">edit_note</span>
        <h3 className="text-sm font-bold">Corrections</h3>
      </div>
      {corrections.length === 0 ? (
        <p className="text-sm leading-6 text-on-surface-variant">No corrections yet.</p>
      ) : (
        <div className="space-y-3">
          {corrections.map((c, index) => (
            <div key={index} className="text-xs font-semibold">
              <span className="text-error line-through decoration-2">{c.original}</span>
              <span className="material-symbols-outlined text-[12px] align-middle mx-1">
                arrow_forward
              </span>
              <span className="text-tertiary bg-tertiary-400/10 px-1 rounded">{c.corrected}</span>
              {c.explanation && (
                <p className="text-[11px] text-on-surface-variant italic mt-1">{c.explanation}</p>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
