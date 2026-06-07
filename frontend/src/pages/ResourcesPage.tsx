import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';

type ResourceSkill = 'grammar' | 'vocab' | 'fluency' | 'logic' | 'pronunciation';
type ResourceScene = 'interview' | 'travel' | 'daily_chat' | 'business';

interface ResourceItem {
  id: string;
  title: string;
  description: string;
  skill: ResourceSkill;
  scene: ResourceScene;
  level: 'A2-B1' | 'B1-B2' | 'B2-C1';
  minutes: number;
  icon: string;
  patterns: string[];
  prompt: string;
}

const SKILLS: Array<{ value: 'all' | ResourceSkill; label: string }> = [
  { value: 'all', label: 'All skills' },
  { value: 'grammar', label: 'Grammar' },
  { value: 'vocab', label: 'Vocabulary' },
  { value: 'fluency', label: 'Fluency' },
  { value: 'logic', label: 'Logic' },
  { value: 'pronunciation', label: 'Pronunciation' },
];

const SCENES: Array<{ value: 'all' | ResourceScene; label: string }> = [
  { value: 'all', label: 'All scenes' },
  { value: 'interview', label: 'Interview' },
  { value: 'travel', label: 'Travel' },
  { value: 'daily_chat', label: 'Daily chat' },
  { value: 'business', label: 'Business' },
];

const RESOURCES: ResourceItem[] = [
  {
    id: 'interview-star',
    title: 'STAR answers for interviews',
    description: 'Turn short project answers into structured stories with situation, task, action, and result.',
    skill: 'logic',
    scene: 'interview',
    level: 'B1-B2',
    minutes: 8,
    icon: 'work_history',
    patterns: ['In my previous role...', 'The challenge was...', 'As a result...'],
    prompt: 'Describe one project using situation, task, action, and result.',
  },
  {
    id: 'interview-weakness',
    title: 'Answering weaknesses naturally',
    description: 'Practice admitting a weakness without sounding negative or memorized.',
    skill: 'fluency',
    scene: 'interview',
    level: 'B1-B2',
    minutes: 6,
    icon: 'psychology',
    patterns: ['One area I am improving is...', 'I have been working on...', 'For example...'],
    prompt: 'Tell the coach one weakness and how you are improving it.',
  },
  {
    id: 'travel-problem',
    title: 'Solving travel problems',
    description: 'Useful phrases for delays, lost luggage, hotel issues, and asking for help politely.',
    skill: 'vocab',
    scene: 'travel',
    level: 'A2-B1',
    minutes: 7,
    icon: 'luggage',
    patterns: ['Could you help me with...', 'My reservation says...', 'Is there another option?'],
    prompt: 'Role-play a travel problem and ask for a solution politely.',
  },
  {
    id: 'daily-opinion',
    title: 'Small talk with opinions',
    description: 'Move beyond one-word answers by adding a reason, an example, and a follow-up question.',
    skill: 'fluency',
    scene: 'daily_chat',
    level: 'A2-B1',
    minutes: 5,
    icon: 'forum',
    patterns: ['I think so because...', 'For me, the main reason is...', 'What about you?'],
    prompt: 'Share your opinion about a daily habit and ask a follow-up question.',
  },
  {
    id: 'business-disagree',
    title: 'Disagreeing in meetings',
    description: 'Practice polite disagreement without sounding blunt or defensive.',
    skill: 'logic',
    scene: 'business',
    level: 'B2-C1',
    minutes: 9,
    icon: 'groups',
    patterns: ['I see your point, but...', 'One concern I have is...', 'Could we consider...?'],
    prompt: 'Disagree with a meeting proposal and suggest a better option.',
  },
  {
    id: 'pronunciation-pauses',
    title: 'Pause control drill',
    description: 'Use short chunks and intentional pauses to sound calmer and more fluent.',
    skill: 'pronunciation',
    scene: 'business',
    level: 'B1-B2',
    minutes: 4,
    icon: 'graphic_eq',
    patterns: ['First...', 'The key point is...', 'Let me give one example...'],
    prompt: 'Explain one business idea using three short chunks and clear pauses.',
  },
  {
    id: 'grammar-past',
    title: 'Past tense story repair',
    description: 'Drill common past tense mistakes in stories about work, travel, and daily life.',
    skill: 'grammar',
    scene: 'daily_chat',
    level: 'A2-B1',
    minutes: 6,
    icon: 'history_edu',
    patterns: ['Yesterday I went...', 'Last week I tried...', 'After that, I realized...'],
    prompt: 'Tell a short story about something that happened last week.',
  },
  {
    id: 'business-update',
    title: 'Clear project updates',
    description: 'Give concise updates with progress, blockers, and next steps.',
    skill: 'vocab',
    scene: 'business',
    level: 'B1-B2',
    minutes: 7,
    icon: 'task_alt',
    patterns: ['We have completed...', 'The current blocker is...', 'Next, we will...'],
    prompt: 'Give a project update with progress, one blocker, and next steps.',
  },
];

export default function ResourcesPage() {
  const [skill, setSkill] = useState<'all' | ResourceSkill>('all');
  const [scene, setScene] = useState<'all' | ResourceScene>('all');

  const filtered = useMemo(
    () => RESOURCES.filter((item) => (skill === 'all' || item.skill === skill) && (scene === 'all' || item.scene === scene)),
    [skill, scene],
  );
  const featured = filtered[0] ?? RESOURCES[0];

  return (
    <div className="mx-auto w-full max-w-container px-4 md:px-10 py-10 pb-28 space-y-6">
      <header className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant">Practice Library</p>
          <h1 className="font-headline-md text-headline-md text-on-surface mt-1">Resources</h1>
          <p className="text-sm leading-6 text-on-surface-variant mt-1 max-w-2xl">
            Pick a focused drill, review useful patterns, then jump straight into a matching speaking session.
          </p>
        </div>
        <Link
          to={`/dashboard?new=1`}
          className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:bg-primary-700 transition-colors"
        >
          <span className="material-symbols-outlined text-[18px]">mic</span>
          New Practice
        </Link>
      </header>

      <section className="grid grid-cols-1 lg:grid-cols-12 gap-4">
        <div className="lg:col-span-7 rounded-xl border border-outline-variant/30 bg-white p-6 shadow-sm">
          <div className="flex items-start gap-4">
            <div className="w-12 h-12 rounded-xl bg-primary-50 text-primary flex items-center justify-center">
              <span className="material-symbols-outlined">{featured.icon}</span>
            </div>
            <div className="min-w-0">
              <p className="text-xs font-semibold uppercase tracking-wider text-primary">Featured drill</p>
              <h2 className="mt-1 text-2xl font-semibold text-on-surface">{featured.title}</h2>
              <p className="mt-2 text-sm leading-6 text-on-surface-variant">{featured.description}</p>
            </div>
          </div>
          <div className="mt-5 flex flex-wrap gap-2">
            {featured.patterns.map((pattern) => (
              <span key={pattern} className="rounded-lg bg-primary-50 px-3 py-1.5 text-xs font-semibold text-primary">
                {pattern}
              </span>
            ))}
          </div>
          <div className="mt-5 rounded-lg bg-surface-low border border-outline-variant/20 p-4">
            <p className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant">Practice prompt</p>
            <p className="mt-1 text-base leading-7 text-on-surface">{featured.prompt}</p>
          </div>
        </div>

        <aside className="lg:col-span-5 rounded-xl border border-outline-variant/30 bg-surface-low p-6">
          <h2 className="text-xl font-semibold text-on-surface">Focus filters</h2>
          <p className="mt-1 text-sm leading-6 text-on-surface-variant">
            Narrow resources by the skill you want to improve and the scene you are practicing.
          </p>
          <div className="mt-5 space-y-4">
            <Segmented label="Skill" value={skill} options={SKILLS} onChange={(value) => setSkill(value as 'all' | ResourceSkill)} />
            <Segmented label="Scene" value={scene} options={SCENES} onChange={(value) => setScene(value as 'all' | ResourceScene)} />
          </div>
        </aside>
      </section>

      <section>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-2xl font-semibold text-on-surface">Resource cards</h2>
          <span className="text-xs font-semibold text-on-surface-variant">{filtered.length} items</span>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          {filtered.map((item) => (
            <ResourceCard key={item.id} item={item} />
          ))}
        </div>
      </section>
    </div>
  );
}

function Segmented<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: Array<{ value: T; label: string }>;
  onChange: (value: T) => void;
}) {
  return (
    <div>
      <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-on-surface-variant">{label}</p>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => {
          const active = option.value === value;
          return (
            <button
              key={option.value}
              type="button"
              onClick={() => onChange(option.value)}
              className={`rounded-lg px-3 py-2 text-xs font-semibold transition-colors ${
                active ? 'bg-primary text-white' : 'bg-white text-on-surface-variant hover:bg-primary-50 hover:text-primary'
              }`}
            >
              {option.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function ResourceCard({ item }: { item: ResourceItem }) {
  return (
    <article className="flex h-full flex-col rounded-xl border border-outline-variant/30 bg-white p-5 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-secondary-400/10 text-secondary flex items-center justify-center">
            <span className="material-symbols-outlined text-[22px]">{item.icon}</span>
          </div>
          <div>
            <h3 className="text-base font-semibold text-on-surface">{item.title}</h3>
            <p className="text-xs font-semibold text-on-surface-variant mt-0.5">
              {item.level} · {item.minutes} min
            </p>
          </div>
        </div>
        <span className="rounded-full bg-tertiary/10 px-2.5 py-1 text-[11px] font-bold capitalize text-tertiary">
          {item.skill}
        </span>
      </div>
      <p className="mt-4 text-sm leading-6 text-on-surface-variant">{item.description}</p>
      <div className="mt-4 flex flex-wrap gap-1.5">
        {item.patterns.slice(0, 2).map((pattern) => (
          <span key={pattern} className="rounded-md bg-surface-low px-2 py-1 text-[11px] font-semibold text-on-surface-variant">
            {pattern}
          </span>
        ))}
      </div>
      <div className="mt-auto pt-5 flex items-center justify-between gap-3">
        <span className="text-xs font-semibold text-on-surface-variant capitalize">
          {item.scene.replace('_', ' ')}
        </span>
        <Link
          to="/dashboard?new=1"
          className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-xs font-bold text-white hover:bg-primary-700 transition-colors"
        >
          Practice
          <span className="material-symbols-outlined text-[16px]">arrow_forward</span>
        </Link>
      </div>
    </article>
  );
}
