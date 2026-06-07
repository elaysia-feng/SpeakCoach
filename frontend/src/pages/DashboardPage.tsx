import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, getPreferences, updatePreferences } from '../lib/api';
import SceneCard from '../components/SceneCard';
import { SCENES } from '../scenes';
import { useAuth } from '../hooks/useAuth';
import type { CoachPersona, Scene, SessionInfo } from '../lib/types';

// 后端列表接口返回 SessionListResponse，包裹完整的 SessionResponse 行，
// 除了 scene / createdAt / sessionId 外，还包括 turnCount + status。
interface SessionListItem extends SessionInfo {
  status?: string;
  turnCount?: number;
}

interface SessionListResponse {
  sessions: SessionListItem[];
}

// 把 persona id 映射到面向用户的简短标签。与 PersonaPicker 的 4 张卡片一致。
const COACH_PERSONA_LABELS: Record<CoachPersona, string> = {
  warm_strict: 'Strict Coach',
  friendly_tutor: 'Friendly Tutor',
  ielts_examiner: 'IELTS Examiner',
  patient_grandma: 'Patient Grandma',
};

// DashboardPage —— 渲染场景卡片；点击一个卡片会创建会话并跳转到 /chat/:id。
// 同时调用 GET /api/sessions 拉取历史会话，列在 "History" 卡片下，并带 Resume 按钮。
// M1-B: 还会在挂载时拉取用户偏好，用于 (1) 在场景卡片 hover 时显示
// "Coach will speak as..." (2) 把 "Speaking Mode" 下拉框 wire 到 PUT /preferences。
export default function DashboardPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user } = useAuth();
  const [sceneError, setSceneError] = useState<string | null>(null);
  const [history, setHistory] = useState<SessionListItem[] | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [scenePickerOpen, setScenePickerOpen] = useState(() => searchParams.get('new') === '1');
  const [persona, setPersona] = useState<CoachPersona>('warm_strict');
  const [savingPersona, setSavingPersona] = useState(false);

  const startScene = async (scene: Scene) => {
    setSceneError(null);
    try {
      const { data } = await api.post<SessionInfo>('/sessions/create_session', { scene: scene.id });
      setScenePickerOpen(false);
      navigate(`/chat/${data.sessionId}`);
    } catch (err) {
      const status = (err as any)?.response?.status;
      const message = (err as any)?.response?.data?.message;
      setSceneError(message || (status ? `Session creation failed (status ${status}).` : 'Session creation failed. Please make sure the Java backend is reachable.'));
    }
  };

  useEffect(() => {
    if (searchParams.get('new') === '1') {
      setScenePickerOpen(true);
    }
  }, [searchParams]);

  useEffect(() => {
    let cancelled = false;
    setHistory(null);
    setHistoryError(null);
    api
      .get<SessionListResponse>('/sessions')
      .then(({ data }) => {
        if (cancelled) return;
        setHistory(Array.isArray(data?.sessions) ? data.sessions : []);
      })
      .catch((err) => {
        if (cancelled) return;
        const status = (err as any)?.response?.status;
        setHistoryError(status ? `Could not load history (status ${status}).` : 'Could not load history.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // M1-B: 拉取当前用户偏好以驱动 hover 提示。
  useEffect(() => {
    let cancelled = false;
    getPreferences()
      .then((prefs) => {
        if (!cancelled) setPersona(prefs.coachPersona);
      })
      .catch(() => {
        // 拉取失败用默认 warm_strict 即可，不阻断页面渲染。
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handlePersonaChange = async (next: CoachPersona) => {
    if (savingPersona || next === persona) return;
    setPersona(next);
    setSavingPersona(true);
    try {
      const updated = await updatePreferences({ coachPersona: next });
      setPersona(updated.coachPersona);
    } catch (err) {
      // 回滚
      setPersona(persona);
      const status = (err as any)?.response?.status;
      setSceneError(status ? `Could not save persona (status ${status}).` : 'Could not save persona preference.');
    } finally {
      setSavingPersona(false);
    }
  };

  // 把 persona 映射到一句短提示,展示在每个 scene 卡片 hover 文本里。
  const coachHintForScene = (_sceneId: string): string => {
    const label = COACH_PERSONA_LABELS[persona];
    return `Coach will speak as: ${label}`;
  };

  const formatCreatedAt = (iso: string | undefined): string => {
    if (!iso) return '—';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '—';
    return d.toLocaleString();
  };

  return (
    <div className="mx-auto w-full max-w-container px-4 md:px-10 py-12 pb-28 lg:pb-12">
      <header className="mb-12">
        <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
          <div>
            <h1 className="text-3xl font-semibold text-on-surface mb-2">
              Welcome back{user ? `, ${user.username}` : ''}! Ready to practice today?
            </h1>
            <p className="text-lg leading-8 text-on-surface-variant">
              Track your progress and pick a scenario to start your AI coaching session.
            </p>
          </div>
          <button
            type="button"
            onClick={() => setScenePickerOpen(true)}
            className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-5 py-3 text-sm font-semibold text-white shadow-soft hover:bg-primary-700 transition-colors"
          >
            <span className="material-symbols-outlined text-[20px]">add</span>
            New Practice
          </button>
        </div>
      </header>

      {sceneError && (
        <div
          role="alert"
          className="flex items-start gap-3 mb-8 bg-error-container/40 border border-error/30 text-error rounded-xl px-4 py-3"
        >
          <span className="material-symbols-outlined text-[20px] mt-0.5">error</span>
          <p className="flex-1 text-sm leading-6">{sceneError}</p>
          <button
            type="button"
            onClick={() => setSceneError(null)}
            className="text-error/80 hover:text-error"
            aria-label="Dismiss error"
          >
            <span className="material-symbols-outlined text-[18px]">close</span>
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <section className="lg:col-span-8 space-y-6">
          <div className="flex items-center justify-between">
            <h2 className="text-2xl font-semibold text-on-surface">Practice Scenarios</h2>
            <span className="text-xs font-semibold text-primary hover:underline cursor-pointer">View all scenarios</span>
          </div>
          <div className="grid gap-3">
            {SCENES.map((s) => (
              <SceneCard
                key={s.id}
                scene={s}
                onSelect={startScene}
                coachHint={coachHintForScene(s.id)}
              />
            ))}
          </div>

          <section id="history-section" className="bg-surface-low p-6 rounded-xl border border-outline-variant/30 scroll-mt-20">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-2xl font-semibold text-on-surface">History</h2>
            </div>
            {historyError && (
              <p className="text-sm text-on-surface-variant">{historyError}</p>
            )}
            {!historyError && history === null && (
              <p className="text-sm text-on-surface-variant">Loading…</p>
            )}
            {!historyError && history && history.length === 0 && (
              <p className="text-sm text-on-surface-variant">No previous sessions yet.</p>
            )}
            {!historyError && history && history.length > 0 && (
              <ul className="divide-y divide-outline-variant/20">
                {history.map((s) => (
                  <li key={s.sessionId} className="flex items-center justify-between gap-4 py-3">
                    <div className="min-w-0">
                      <p className="text-base font-medium text-on-surface truncate">
                        {s.scene || 'unknown'} · <span className="text-on-surface-variant text-sm">{s.status || 'finished'}</span> · <span className="text-on-surface-variant text-sm">{s.turnCount ?? 0} turns</span>
                      </p>
                      <p className="text-xs text-on-surface-variant mt-0.5">
                        {formatCreatedAt(s.createdAt)}
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => navigate(`/chat/${s.sessionId}`)}
                      className="shrink-0 text-sm font-semibold text-primary hover:underline"
                    >
                      Resume
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </section>

        <aside className="lg:col-span-4 space-y-6">
          <section className="bg-surface-low p-6 rounded-xl border border-outline-variant/30">
            <h2 className="text-2xl font-semibold text-on-surface mb-6">Session Config</h2>
            <div className="space-y-6">
              <div>
                <label className="block text-sm font-medium text-on-surface-variant mb-2">Coach persona</label>
                <select
                  value={persona}
                  onChange={(e) => handlePersonaChange(e.target.value as CoachPersona)}
                  disabled={savingPersona}
                  className="w-full bg-white border border-outline-variant rounded-lg px-4 py-3 text-on-surface focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none"
                >
                  {Object.entries(COACH_PERSONA_LABELS).map(([id, label]) => (
                    <option key={id} value={id}>{label}</option>
                  ))}
                </select>
                <p className="text-xs text-on-surface-variant mt-2">
                  Changes apply to your next turn. Manage in <a href="/settings" className="text-primary hover:underline">Settings</a> for the full picker.
                </p>
              </div>
            </div>
          </section>

          <section className="bg-primary-400 p-6 rounded-xl text-white">
            <h3 className="text-2xl font-semibold mb-4">Weekly Progress</h3>
            <div className="flex items-end justify-between mb-2">
              <span className="text-sm font-medium">Current Goal: 80%</span>
              <span className="text-xs font-semibold">12/15 Sessions</span>
            </div>
            <div className="w-full h-3 bg-white/20 rounded-full overflow-hidden">
              <div className="h-full bg-white w-4/5 rounded-full" />
            </div>
            <p className="text-base leading-7 mt-4 opacity-90">
              Keep momentum with one focused session today.
            </p>
          </section>

          <section className="relative overflow-hidden rounded-xl h-48 bg-primary-800 p-6 flex flex-col justify-end">
            <div className="absolute inset-0 bg-gradient-to-br from-primary/30 via-secondary/20 to-tertiary/20" />
            <div className="relative text-white">
              <p className="text-xs font-semibold mb-1 uppercase tracking-wider">New Content</p>
              <h4 className="text-2xl font-semibold leading-tight">Advanced Negotiation Tactics</h4>
            </div>
          </section>
        </aside>
      </div>

      <div className="fixed bottom-0 left-0 w-full z-50 flex justify-around items-center px-4 py-3 bg-surface/90 backdrop-blur-xl lg:hidden border-t border-outline-variant/30 shadow-[0_-4px_20px_rgba(0,0,0,0.05)]">
        {['home', 'history', 'mic', 'person'].map((icon, index) => (
          <div key={icon} className={`flex flex-col items-center justify-center ${index === 0 ? 'bg-secondary-400 text-white rounded-full px-4 py-1' : 'text-on-surface-variant'}`}>
            <span className="material-symbols-outlined">{icon}</span>
            <span className="text-[11px] font-semibold">{['Home', 'History', 'Speak', 'Profile'][index]}</span>
          </div>
        ))}
      </div>
      {scenePickerOpen && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/30 px-4">
          <section className="w-full max-w-2xl rounded-xl bg-white p-6 shadow-lift border border-outline-variant/30">
            <div className="flex items-start justify-between gap-4 mb-5">
              <div>
                <h2 className="text-2xl font-semibold text-on-surface">Choose a scenario</h2>
                <p className="text-sm leading-6 text-on-surface-variant mt-1">
                  Pick one scene before creating a new speaking session.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setScenePickerOpen(false)}
                className="w-10 h-10 rounded-full flex items-center justify-center text-on-surface-variant hover:bg-surface-low"
                aria-label="Close scene picker"
              >
                <span className="material-symbols-outlined">close</span>
              </button>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              {SCENES.map((scene) => (
                <button
                  type="button"
                  key={scene.id}
                  onClick={() => startScene(scene)}
                  className="text-left rounded-xl border border-outline-variant/40 p-4 hover:border-primary hover:bg-primary-50 transition-colors"
                >
                  <span className="material-symbols-outlined text-primary mb-3">
                    {scene.id === 'interview' ? 'work' : scene.id === 'travel' ? 'restaurant' : scene.id === 'business' ? 'groups' : 'forum'}
                  </span>
                  <h3 className="text-lg font-semibold text-on-surface">{scene.name}</h3>
                  <p className="text-sm leading-6 text-on-surface-variant mt-1">{scene.description}</p>
                </button>
              ))}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
