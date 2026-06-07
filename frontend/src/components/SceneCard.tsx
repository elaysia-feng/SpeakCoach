import type { Scene } from '../lib/types';
import Button from './Button';

interface Props {
  scene: Scene;
  onSelect: (scene: Scene) => void;
  coachHint?: string;
}

// SceneCard —— 仪表板卡片，展示表情符号、名称、描述以及 "开始练习" 行动按钮。
// M1-B: 当传入 coachHint 时，hover 会显示 "Coach will speak as..." 提示。
export default function SceneCard({ scene, onSelect, coachHint }: Props) {
  const icon = scene.id === 'interview'
    ? 'work'
    : scene.id === 'travel'
      ? 'restaurant'
      : scene.id === 'business'
        ? 'groups'
        : 'forum';
  const level = scene.id === 'interview' ? ['Hard', 'bg-error/10 text-error border-error/20'] : scene.id === 'daily_chat' ? ['Easy', 'bg-tertiary/10 text-tertiary border-tertiary/20'] : ['Medium', 'bg-secondary/10 text-secondary border-secondary/20'];

  return (
    <article className="group relative bg-surface-lowest border border-outline-variant/50 rounded-xl p-6 shadow-soft hover:shadow-lift hover:-translate-y-1 transition-all flex flex-col md:flex-row items-start md:items-center gap-6">
      <div className="w-16 h-16 rounded-xl bg-primary-100 flex items-center justify-center shrink-0">
        <span className="material-symbols-outlined text-primary text-3xl">{icon}</span>
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex flex-wrap items-center gap-3 mb-1">
          <h3 className="text-2xl font-semibold text-on-surface">{scene.name}</h3>
          <span className={`px-3 py-1 rounded-full text-xs font-semibold border ${level[1]}`}>{level[0]}</span>
        </div>
        <p className="text-base leading-7 text-on-surface-variant">{scene.description}</p>
        {coachHint && (
          <p className="mt-2 text-sm leading-6 text-primary/80 opacity-0 group-hover:opacity-100 transition-opacity">
            <span className="material-symbols-outlined text-[16px] align-text-bottom mr-1">record_voice_over</span>
            {coachHint}
          </p>
        )}
      </div>
      <Button onClick={() => onSelect(scene)} className="whitespace-nowrap px-6 py-3">
        Start Practice
      </Button>
    </article>
  );
}
