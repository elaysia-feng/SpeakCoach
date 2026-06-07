interface ShadowAnswerProps {
  answer: string;
  active?: boolean;
  onPractice?: (answer: string) => void;
}

export default function ShadowAnswer({
  answer,
  active = false,
  onPractice,
}: ShadowAnswerProps) {
  const hasAnswer = Boolean(answer?.trim());
  return (
    <section className="bg-white border border-outline-variant/20 rounded-xl p-4 shadow-sm">
      <div className="flex items-center justify-between gap-3 mb-3">
        <div className="flex items-center gap-2 text-secondary">
          <span className="material-symbols-outlined text-[20px]">auto_fix_high</span>
          <h3 className="text-sm font-bold">Shadow Answer</h3>
        </div>
        <button
          type="button"
          onClick={() => onPractice?.(answer)}
          disabled={!hasAnswer || !onPractice}
          className={`inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-bold transition-colors ${
            active
              ? 'bg-secondary text-white'
              : 'bg-secondary-400/10 text-secondary hover:bg-secondary-400/20 disabled:opacity-50 disabled:hover:bg-secondary-400/10'
          }`}
        >
          <span className="material-symbols-outlined text-[16px]">mic</span>
          {active ? 'Practicing' : 'Practice'}
        </button>
      </div>
      <div className="bg-secondary-400/10 p-3 rounded-lg border border-secondary/10">
        <p className="text-base leading-7 text-on-surface">
          {answer || 'Your upgraded answer will appear here after the first turn.'}
        </p>
      </div>
    </section>
  );
}
