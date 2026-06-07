import { useEffect, useState } from 'react';
import { getPreferences, updatePreferences } from '../lib/api';
import PersonaPicker from '../components/PersonaPicker';
import type { CoachPersona, VoiceId } from '../lib/types';

// SettingsPage —— M1-B 用户偏好设置页。
//   - 教练人格选择（4 张卡片，受控） —— PersonaPicker
//   - 音色下拉（当前仅 linqian_voice，预留扩展）
//   - 顶部错误条 + 自动保存
//
// 设计意图：把 persona 切换的反馈下沉到 Settings 页本身，Dashboard
// 不再重复出现"Speaking Mode"那类没绑 API 的占位控件。
export default function SettingsPage() {
  const [persona, setPersona] = useState<CoachPersona>('warm_strict');
  const [voice, setVoice] = useState<VoiceId>('linqian_voice');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    setLoading(true);
    getPreferences()
      .then((prefs) => {
        if (cancelled) return;
        setPersona(prefs.coachPersona);
        setVoice(prefs.preferredVoice);
      })
      .catch((err) => {
        if (cancelled) return;
        const status = (err as any)?.response?.status;
        setError(status ? `Could not load preferences (status ${status}).` : 'Could not load preferences.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handlePersonaChange = (next: CoachPersona) => {
    setPersona(next);
    setSavedAt(null);
    void persist(next, voice);
  };

  const handleVoiceChange = (next: VoiceId) => {
    setVoice(next);
    setSavedAt(null);
    void persist(persona, next);
  };

  const persist = async (nextPersona: CoachPersona, nextVoice: VoiceId) => {
    setSaving(true);
    setError(null);
    try {
      const updated = await updatePreferences({
        coachPersona: nextPersona,
        preferredVoice: nextVoice,
      });
      setPersona(updated.coachPersona);
      setVoice(updated.preferredVoice);
      setSavedAt(new Date().toLocaleTimeString());
    } catch (err) {
      const status = (err as any)?.response?.status;
      setError(status ? `Could not save preferences (status ${status}).` : 'Could not save preferences.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mx-auto w-full max-w-3xl px-4 md:px-10 py-12 pb-28">
      <header className="mb-8">
        <h1 className="text-3xl font-semibold text-on-surface mb-2">Coach &amp; Voice</h1>
        <p className="text-base leading-7 text-on-surface-variant">
          Pick how your AI coach talks to you, and which voice replies in audio. Changes
          take effect on your next turn.
        </p>
      </header>

      {error && (
        <div
          role="alert"
          className="flex items-start gap-3 mb-6 bg-error-container/40 border border-error/30 text-error rounded-xl px-4 py-3"
        >
          <span className="material-symbols-outlined text-[20px] mt-0.5">error</span>
          <p className="flex-1 text-sm leading-6">{error}</p>
        </div>
      )}

      <section className="bg-surface-low p-6 rounded-xl border border-outline-variant/30 mb-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-2xl font-semibold text-on-surface">Coach persona</h2>
          <span className="text-xs font-semibold text-on-surface-variant">
            {saving ? 'Saving…' : savedAt ? `Saved at ${savedAt}` : loading ? 'Loading…' : ''}
          </span>
        </div>
        {loading ? (
          <p className="text-sm text-on-surface-variant">Loading…</p>
        ) : (
          <PersonaPicker selected={persona} onChange={handlePersonaChange} disabled={saving} />
        )}
      </section>

      <section className="bg-surface-low p-6 rounded-xl border border-outline-variant/30">
        <h2 className="text-2xl font-semibold text-on-surface mb-2">Voice</h2>
        <p className="text-sm leading-6 text-on-surface-variant mb-4">
          Choose the TTS voice that reads the AI replies. (More voices coming soon.)
        </p>
        <select
          value={voice}
          onChange={(e) => handleVoiceChange(e.target.value as VoiceId)}
          disabled={loading || saving}
          className="w-full bg-white border border-outline-variant rounded-lg px-4 py-3 text-on-surface focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none"
        >
          <option value="linqian_voice">Lin Qian (default)</option>
        </select>
      </section>
    </div>
  );
}
