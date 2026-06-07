import type { CoachPersona } from '../lib/types';

// 4 张人设卡 —— 与后端 `UserPreferencesService.VALID_PERSONAS` 一一对应。
// icon / 标题 / 描述都为 UI 文案；选中状态由外层受控（selected）。
export interface PersonaOption {
  id: CoachPersona;
  title: string;
  description: string;
  tagline: string;
  icon: string;
}

export const PERSONA_OPTIONS: PersonaOption[] = [
  {
    id: 'warm_strict',
    title: 'Strict Coach',
    description: 'Balanced coach — kind but calls out the one error that matters most.',
    tagline: 'Warm but strict',
    icon: 'gavel',
  },
  {
    id: 'friendly_tutor',
    title: 'Friendly Tutor',
    description: 'Cheerful and patient. Short sentences, simple words, lots of encouragement.',
    tagline: 'Casual and warm',
    icon: 'volunteer_activism',
  },
  {
    id: 'ielts_examiner',
    title: 'IELTS Examiner',
    description: 'Formal, precise, scores against IELTS band descriptors. Uses STAR structure.',
    tagline: 'Formal and rigorous',
    icon: 'school',
  },
  {
    id: 'patient_grandma',
    title: 'Patient Grandma',
    description: 'Slow, gentle, simple vocabulary. Repeats encouragement and explains kindly.',
    tagline: 'Slow and gentle',
    icon: 'elderly_woman',
  },
];

interface PersonaPickerProps {
  selected: CoachPersona;
  onChange: (persona: CoachPersona) => void;
  disabled?: boolean;
}

// PersonaPicker —— 4 张人设卡的受控网格。选中时外环 + 浅背景。
export default function PersonaPicker({ selected, onChange, disabled }: PersonaPickerProps) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      {PERSONA_OPTIONS.map((opt) => {
        const isSelected = opt.id === selected;
        return (
          <button
            key={opt.id}
            type="button"
            onClick={() => !disabled && onChange(opt.id)}
            disabled={disabled}
            className={[
              'text-left rounded-xl p-4 border transition-all',
              isSelected
                ? 'border-primary bg-primary-50 ring-2 ring-primary/40'
                : 'border-outline-variant/40 hover:border-primary hover:bg-primary-50/40',
              disabled ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer',
            ].join(' ')}
            aria-pressed={isSelected}
          >
            <div className="flex items-start gap-3">
              <div
                className={[
                  'w-12 h-12 rounded-xl flex items-center justify-center shrink-0',
                  isSelected ? 'bg-primary text-white' : 'bg-primary-100 text-primary',
                ].join(' ')}
              >
                <span className="material-symbols-outlined text-[24px]">{opt.icon}</span>
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <h3 className="text-base font-semibold text-on-surface">{opt.title}</h3>
                  {isSelected && (
                    <span className="material-symbols-outlined text-primary text-[18px]">
                      check_circle
                    </span>
                  )}
                </div>
                <p className="text-xs font-medium text-primary mb-1">{opt.tagline}</p>
                <p className="text-sm leading-6 text-on-surface-variant">{opt.description}</p>
              </div>
            </div>
          </button>
        );
      })}
    </div>
  );
}
