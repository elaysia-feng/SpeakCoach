import { useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Panel, PanelGroup, PanelResizeHandle } from 'react-resizable-panels';
import { api } from '../lib/api';
import ChatBubble from '../components/ChatBubble';
import CorrectionsPanel from '../components/CorrectionsPanel';
import ShadowAnswer from '../components/ShadowAnswer';
import { SCENES } from '../scenes';
import type {
  AbilityScore,
  ChatHistoryResponse,
  ChatResponse,
  ChatTurn,
  Correction,
  SessionSummary,
  SessionInfo,
  SpeechMetrics,
  WordTimestamp,
} from '../lib/types';

export default function ChatPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();

  const [sceneId, setSceneId] = useState<string>(
    () => (sessionId?.startsWith('local-') ? sessionId.replace('local-', '') : 'interview'),
  );
  const scene = SCENES.find((s) => s.id === sceneId) ?? SCENES[0];

  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [listening, setListening] = useState(false);
  const [audioPhase, setAudioPhase] = useState<'idle' | 'uploading' | 'analyzing'>('idle');
  const [shadowPractice, setShadowPractice] = useState<{ target: string } | null>(null);
  const [speechError, setSpeechError] = useState<string | null>(null);
  const [report, setReport] = useState<SessionSummary | null>(null);
  const [sendError, setSendError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const recognitionRef = useRef<any>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const localAudioUrlsRef = useRef<string[]>([]);
  const transcriptRef = useRef('');
  const isTogglingRef = useRef(false);
  const mountedRef = useRef(true);

  const lastResponse = [...turns].reverse().find((t) => t.role === 'ai' && t.response)?.response;
  const corrections = useMemo(() => normalizeCorrections(lastResponse?.corrections), [lastResponse]);
  const ability = useMemo(() => normalizeAbility(lastResponse?.abilityScore), [lastResponse]);
  const pronunciationScore = ability?.pronunciation ?? normalizeNumber(lastResponse?.pronunciationScore);
  const speechMetrics = useMemo(() => normalizeSpeechMetrics(lastResponse?.speechMetrics), [lastResponse]);
  const wordTimestamps = useMemo(() => normalizeWordTimestamps(lastResponse?.wordTimestamps), [lastResponse]);
  const summary = useMemo(() => report ?? normalizeSummary(lastResponse?.summary), [lastResponse, report]);
  const hasPendingAudio = turns.some((turn) => turn.role === 'user' && turn.pending);
  const statusLabel = listening
    ? 'Listening'
    : audioPhase === 'uploading'
      ? 'Uploading'
      : audioPhase === 'analyzing'
        ? 'Analyzing speech'
        : loading
          ? 'Thinking'
          : 'Ready';
  const statusHint = speechError
    ?? (listening
      ? (shadowPractice ? 'Recording your shadowing practice...' : 'Coach is listening...')
      : audioPhase === 'uploading'
        ? 'Uploading your recording...'
        : audioPhase === 'analyzing'
          ? 'Analyzing speech and preparing feedback...'
          : hasPendingAudio
            ? 'Showing your local recording while the server analyzes it.'
          : shadowPractice
            ? 'Record yourself reading the shadow answer.'
          : input
            ? 'Text fallback is ready. Press Enter or the send button.'
            : 'Record your answer, transcript will appear after upload.');

  useEffect(() => {
    // 滚到最底。Form 现在是 flex 子元素（不是 absolute），不再需要预留余量。
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [turns, loading]);

  useEffect(() => {
    return () => {
      mountedRef.current = false;
      const recognition = recognitionRef.current;
      recognitionRef.current = null;
      try {
        recognition?.stop?.();
      } catch {
        // 忽略：识别可能已经停止。
      }
      try {
        mediaRecorderRef.current?.stop();
      } catch {
        // 忽略：录音可能已经停止。
      }
      mediaStreamRef.current?.getTracks().forEach((track) => track.stop());
      localAudioUrlsRef.current.forEach((url) => URL.revokeObjectURL(url));
      localAudioUrlsRef.current = [];
    };
  }, []);

  // 当 session id 是真实服务器 id 时，从后端解析真实场景。
  useEffect(() => {
    if (!sessionId || sessionId.startsWith('local-')) return;
    let cancelled = false;
    api
      .get<SessionInfo>(`/sessions/${sessionId}`)
      .then(({ data }) => {
        if (cancelled) return;
        if (data?.scene) setSceneId(data.scene);
      })
      .catch(() => {
        // 场景的兜底值已经在上面设置为 'interview'；失败时静默处理。
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useEffect(() => {
    if (!sessionId || sessionId.startsWith('local-')) return;
    let cancelled = false;
    api
      .get<ChatHistoryResponse>('/chat/history', { params: { sessionId } })
      .then(({ data }) => {
        if (cancelled || !Array.isArray(data?.turns)) return;
        const restored: ChatTurn[] = [];
        data.turns.forEach((item) => {
          restored.push({
            role: 'user',
            text: item.userText || '',
            userAudioUrl: item.userAudioUrl,
          });
          restored.push({
            role: 'ai',
            text: item.aiReply || '',
            response: {
              turnId: item.turnId,
              userText: item.userText,
              userAudioUrl: item.userAudioUrl,
              aiReply: item.aiReply,
              audioUrl: item.audioUrl,
              corrections: normalizeCorrections(item.corrections),
              shadowAnswer: item.shadowAnswer,
              abilityScore: item.abilityScore,
              pronunciationScore: item.pronunciationScore,
              strategy: item.strategy,
              summary: item.summary,
              speechMetrics: normalizeSpeechMetrics(item.speechMetrics),
              wordTimestamps: normalizeWordTimestamps(item.wordTimestamps),
            },
          });
        });
        setTurns(restored);
      })
      .catch(() => {
        // 历史拉取失败不阻断本轮练习。
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  const send = async (e?: FormEvent) => {
    e?.preventDefault();
    const text = input.trim();
    if (!text || loading || !sessionId) return;
    setInput('');
    setSendError(null);
    setTurns((prev) => [...prev, { role: 'user', text }]);
    setLoading(true);
    try {
      const { data } = await api.post<ChatResponse>('/chat', { sessionId, userText: text });
      const normalized = {
        ...data,
        corrections: normalizeCorrections(data.corrections),
        speechMetrics: normalizeSpeechMetrics(data.speechMetrics),
        wordTimestamps: normalizeWordTimestamps(data.wordTimestamps),
      };
      setTurns((prev) => [
        ...prev,
        { role: 'ai', text: normalized.aiReply, response: normalized },
      ]);
    } catch (err) {
      const status = (err as any)?.response?.status;
      const message = (err as any)?.response?.data?.message;
      const friendly = message || mapSendError(status);
      setSendError(friendly);
      setTurns((prev) => [
        ...prev,
        {
          role: 'ai',
          text: friendly,
          response: {
            turnId: prev.length,
            userText: text,
            userAudioUrl: '',
            aiReply: friendly,
            audioUrl: '',
            corrections: [],
            shadowAnswer: '',
            abilityScore: null,
            strategy: 'normal_follow_up',
            summary: null,
            speechMetrics: null,
            wordTimestamps: null,
          },
        },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const toggleListening = async () => {
    if (isTogglingRef.current) return;
    if (loading && !listening) return;
    isTogglingRef.current = true;
    if (listening && mediaRecorderRef.current) {
      stopRecording();
      isTogglingRef.current = false;
      return;
    }

    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      setSpeechError('This browser does not support audio recording.');
      isTogglingRef.current = false;
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaStreamRef.current = stream;
      audioChunksRef.current = [];
      transcriptRef.current = '';
      setInput('');
      setSpeechError(null);

      const mimeType = pickRecordingMimeType();
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
      mediaRecorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };
      recorder.onerror = () => {
        setSpeechError('Recording failed. Please try again.');
        cleanupRecording();
      };
      recorder.onstop = () => {
        const chunks = audioChunksRef.current;
        const type = recorder.mimeType || 'audio/webm';
        cleanupRecording();
        if (!chunks.length) {
          setSpeechError('No audio was captured.');
          return;
        }
        const blob = new Blob(chunks, { type });
        const localUrl = URL.createObjectURL(blob);
        localAudioUrlsRef.current.push(localUrl);
        const localId = `local-audio-${Date.now()}-${Math.random().toString(16).slice(2)}`;
        const activePractice = shadowPractice;
        const interimText = transcriptRef.current.trim() || input.trim() || 'Waiting for server transcript...';
        setTurns((prev) => [
          ...prev,
          {
            role: 'user',
            text: interimText,
            userAudioUrl: localUrl,
            localId,
            pending: true,
            practiceMode: activePractice ? 'shadowing' : undefined,
            practiceTarget: activePractice?.target,
          },
        ]);
        void sendAudio(blob, transcriptRef.current, localId, activePractice);
        setShadowPractice(null);
      };

      startClientTranscript();
      recorder.start();
      setListening(true);
    } catch {
      setSpeechError('Could not start recording. Please allow microphone access.');
      cleanupRecording();
    } finally {
      isTogglingRef.current = false;
    }
  };

  const stopRecording = () => {
    setListening(false);
    try {
      recognitionRef.current?.stop?.();
    } catch {
      // 忽略：实时字幕识别可能已经停止。
    }
    recognitionRef.current = null;
    try {
      if (mediaRecorderRef.current?.state === 'recording') {
        mediaRecorderRef.current.stop();
      }
    } catch {
      cleanupRecording();
    }
  };

  const startShadowPractice = (target: string) => {
    const normalized = target.trim();
    if (!normalized || loading || listening) return;
    setShadowPractice({ target: normalized });
    setInput('');
    setSpeechError(null);
  };

  const cleanupRecording = () => {
    setListening(false);
    mediaRecorderRef.current = null;
    mediaStreamRef.current?.getTracks().forEach((track) => track.stop());
    mediaStreamRef.current = null;
  };

  const startClientTranscript = () => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) {
      return;
    }
    const recognition = new SpeechRecognition();
    recognition.lang = 'en-US';
    recognition.continuous = true;
    recognition.interimResults = true;
    let finalText = '';
    let latestInterim = '';
    recognition.onresult = (event: any) => {
      latestInterim = '';
      for (let i = 0; i < event.results.length; i += 1) {
        const result = event.results[i];
        if (result.isFinal) {
          finalText += result[0].transcript;
        } else {
          latestInterim = result[0].transcript;
        }
      }
      const transcript = (finalText + (latestInterim || '')).trim();
      transcriptRef.current = transcript;
      setInput('');
    };
    recognition.onerror = (event: any) => {
      if (!mountedRef.current || recognitionRef.current !== recognition) return;
      setSpeechError(event.error ? `Live transcript error: ${event.error}` : 'Live transcript failed.');
      recognitionRef.current = null;
    };
    recognition.onend = () => {
      if (!mountedRef.current || recognitionRef.current !== recognition) return;
      recognitionRef.current = null;
    };
    recognitionRef.current = recognition;
    try {
      recognition.start();
    } catch {
      recognitionRef.current = null;
    }
  };

  const sendAudio = async (
    blob: Blob,
    clientTranscript: string,
    localId: string,
    practice: { target: string } | null,
  ) => {
    if (loading || !sessionId) return;
    setLoading(true);
    setAudioPhase('uploading');
    setSendError(null);
    try {
      const form = new FormData();
      form.append('sessionId', sessionId);
      form.append('clientTranscript', clientTranscript || '');
      if (practice?.target) {
        form.append('practiceMode', 'shadowing');
        form.append('practiceTarget', practice.target);
      }
      form.append('audio', blob, `turn.${blob.type.includes('wav') ? 'wav' : 'webm'}`);
      const { data } = await api.post<ChatResponse>('/chat/audio', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (event) => {
          if (!event.total || event.loaded >= event.total) {
            setAudioPhase('analyzing');
          }
        },
      });
      const normalized = {
        ...data,
        corrections: normalizeCorrections(data.corrections),
        speechMetrics: normalizeSpeechMetrics(data.speechMetrics),
        wordTimestamps: normalizeWordTimestamps(data.wordTimestamps),
      };
      const transcript = normalized.userText || clientTranscript || 'Voice transcript unavailable';
      setTurns((prev) => {
        const localTurn = prev.find((turn) => turn.localId === localId);
        if (localTurn?.userAudioUrl?.startsWith('blob:')) {
          URL.revokeObjectURL(localTurn.userAudioUrl);
          localAudioUrlsRef.current = localAudioUrlsRef.current.filter((url) => url !== localTurn.userAudioUrl);
        }
        return [
          ...prev.map((turn) => turn.localId === localId
            ? {
                ...turn,
                text: transcript || turn.text || 'Voice message',
                userAudioUrl: normalized.userAudioUrl || turn.userAudioUrl,
                pending: false,
              }
            : turn),
          { role: 'ai', text: normalized.aiReply, response: normalized },
        ];
      });
      setInput(transcript);
    } catch (err) {
      const status = (err as any)?.response?.status;
      const message = (err as any)?.response?.data?.message;
      const friendly = message || mapSendError(status);
      setSendError(friendly);
      setTurns((prev) => prev.map((turn) => turn.localId === localId
        ? { ...turn, pending: false }
        : turn));
    } finally {
      setLoading(false);
      setAudioPhase('idle');
    }
  };

  const finishSession = async () => {
    if (!sessionId || sessionId.startsWith('local-')) {
      navigate('/dashboard');
      return;
    }
    const body = {
      summary: JSON.stringify(summary ?? buildLocalSummary(turns), null, 0),
      grammarScore: ability?.grammar,
      vocabularyScore: ability?.vocabulary,
      fluencyScore: ability?.fluency,
      logicScore: ability?.logic,
      commonErrors: corrections.map((c) => c.original).filter(Boolean),
    };
    try {
      await api.post(`/sessions/${sessionId}/finish`, body);
      setReport(summary ?? buildLocalSummary(turns));
      navigate(`/report/${sessionId}`);
    } catch (err) {
      const status = (err as any)?.response?.status;
      const message = (err as any)?.response?.data?.message;
      setSendError(message || mapSendError(status));
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  };

  return (
    <PanelGroup direction="horizontal" autoSaveId="speakcoach-chat-layout-v3" className="h-screen overflow-hidden bg-surface text-on-surface">
      <Panel defaultSize={18} minSize={18} maxSize={28} order={1} className="hidden lg:block">
        <aside className="flex flex-col w-full h-full min-h-0 bg-white border-r border-outline-variant/30 p-6 overflow-y-auto custom-scrollbar">
          <div className="mb-12">
            <h1 className="text-2xl font-semibold text-primary mb-1">SpeakCoach</h1>
            <p className="text-xs font-semibold text-on-surface-variant opacity-70">AI Session • Active</p>
          </div>

        <div className="bg-surface-container rounded-xl p-4 mb-6">
          <div className="flex items-center gap-2 mb-2">
            <span className="material-symbols-outlined text-primary" style={{ fontVariationSettings: "'FILL' 1" }}>work</span>
            <span className="text-sm font-medium text-primary">{scene.name}</span>
          </div>
          <div className="flex justify-between items-center mt-3">
            <span className="text-xs font-semibold text-on-surface-variant">Turns</span>
            <span className="text-xs font-bold text-on-surface">{Math.max(1, turns.filter((t) => t.role === 'user').length)}/10</span>
          </div>
          <div className="w-full bg-outline-variant/30 h-1.5 rounded-full mt-2">
            <div className="bg-primary h-full rounded-full" style={{ width: `${Math.min(100, turns.filter((t) => t.role === 'user').length * 10)}%` }} />
          </div>
        </div>

        <div className="flex items-center gap-3 px-4 py-3 bg-primary-50 border border-primary/20 rounded-xl mb-6">
          <div className="relative">
            <div className={`w-3 h-3 rounded-full ${listening || audioPhase !== 'idle' ? 'bg-primary' : 'bg-outline'}`} />
            {listening && <div className="absolute inset-0 w-3 h-3 bg-primary rounded-full animate-ping opacity-75" />}
          </div>
          <span className="text-sm font-bold text-primary">{statusLabel}</span>
        </div>

        <div className="space-y-6 flex-grow">
          <p className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant">Performance</p>
          <AbilityBar label="Grammar" value={ability?.grammar ?? 60} color="bg-primary" />
          <AbilityBar label="Vocabulary" value={ability?.vocabulary ?? 60} color="bg-primary" />
          <AbilityBar label="Fluency" value={ability?.fluency ?? 60} color="bg-secondary" />
          <AbilityBar label="Logic" value={ability?.logic ?? 60} color="bg-tertiary" />
          <AbilityBar label="Pronunciation" value={pronunciationScore ?? 60} color="bg-secondary" />
        </div>

        <button onClick={finishSession} className="w-full mt-12 py-3 px-4 border border-error/30 text-error font-medium rounded-xl hover:bg-error/5 transition-colors flex items-center justify-center gap-2">
          <span className="material-symbols-outlined text-[20px]">logout</span>
          End Practice
        </button>
        </aside>
      </Panel>

      <PanelResizeHandle className="hidden lg:block w-px bg-outline-variant/30 hover:bg-primary/40 active:bg-primary/60 transition-colors cursor-col-resize" />

      <Panel defaultSize={46} minSize={32} order={2}>
        <main className="flex flex-col h-full relative bg-surface">
        <header className="lg:hidden flex items-center justify-between p-4 bg-surface border-b border-outline-variant/30">
          <div className="flex items-center gap-3">
            <span className="material-symbols-outlined text-primary">menu</span>
            <span className="text-2xl font-semibold text-primary">SpeakCoach</span>
          </div>
          <span className="text-xs font-semibold px-2 py-1 bg-primary-400 text-white rounded-lg">{turns.filter((t) => t.role === 'user').length}/10</span>
        </header>

        <div ref={scrollRef} className="flex-grow min-h-0 overflow-y-auto custom-scrollbar p-6 lg:p-10 space-y-6">
          {sendError && (
            <div className="flex items-start gap-3 max-w-[85%] bg-error-container/40 border border-error/30 text-error rounded-xl px-4 py-3">
              <span className="material-symbols-outlined text-[20px] mt-0.5">error</span>
              <div className="flex-1 text-sm leading-6">
                <p className="font-semibold">{sendError}</p>
              </div>
              <button
                type="button"
                onClick={() => setSendError(null)}
                className="text-error/80 hover:text-error"
                aria-label="Dismiss error"
              >
                <span className="material-symbols-outlined text-[18px]">close</span>
              </button>
            </div>
          )}
          {turns.length === 0 && (
            <div className="flex items-start gap-3 max-w-[85%]">
              <Avatar icon="smart_toy" tone="ai" />
              <div className="bg-surface-low border border-outline-variant/20 px-4 py-3 rounded-2xl rounded-tl-none shadow-sm">
                <p className="text-base leading-7 text-on-surface">
                  Tell me about yourself in English. I will correct grammar, upgrade your answer, and ask the next question.
                </p>
              </div>
            </div>
          )}
          {turns.map((t, i) => (
            <ChatBubble
              key={i}
              role={t.role === 'user' ? 'user' : 'assistant'}
              text={t.text}
              audioUrl={
                t.role === 'user'
                  ? t.userAudioUrl ?? undefined
                  : t.response?.audioUrl || undefined
              }
              practiceMode={t.practiceMode}
              pending={t.pending}
            />
          ))}
          {input && listening && (
            <div className="flex items-start justify-end gap-3 ml-auto max-w-[85%] opacity-80">
              <div className="bg-surface-highest/50 border border-dashed border-primary/30 px-4 py-3 rounded-2xl rounded-tr-none">
                <p className="text-base leading-7 text-on-surface-variant italic">"{input}"</p>
                <div className="mt-2 flex items-center gap-1">
                  <span className="w-1.5 h-1.5 bg-primary rounded-full animate-bounce" />
                  <span className="w-1.5 h-1.5 bg-primary rounded-full animate-bounce [animation-delay:0.2s]" />
                  <span className="w-1.5 h-1.5 bg-primary rounded-full animate-bounce [animation-delay:0.4s]" />
                </div>
              </div>
            </div>
          )}
          {loading && <p className="text-sm text-on-surface-variant italic">{statusLabel}...</p>}
        </div>

        <form onSubmit={send} className="flex-shrink-0 p-6 lg:p-10 bg-gradient-to-t from-surface via-surface/95 to-transparent pt-12">
          <div className="max-w-2xl mx-auto">
            <div className="text-center mb-4">
              <p className="text-sm font-medium text-on-surface-variant">
                {statusHint}
              </p>
            </div>
            <div className="flex items-center justify-center gap-1.5 h-10 mb-4">
              {[0.1, 0.3, 0.5, 0.2, 0.4, 0.6, 0.1].map((delay, index) => (
                <div key={index} className={`wave-bar w-1.5 rounded-full ${listening ? 'bg-primary' : 'bg-primary/20'}`} style={{ animationDelay: `${delay}s`, animationPlayState: listening ? 'running' : 'paused' }} />
              ))}
            </div>
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              disabled={loading}
              rows={2}
              placeholder={shadowPractice ? 'Record yourself reading the shadow answer.' : 'Record your answer, transcript will appear after upload.'}
              className="w-full mb-4 px-4 py-3 bg-white border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all resize-none disabled:opacity-60"
            />
            <div className="flex items-center justify-center gap-8">
              <button type="button" onClick={() => { setInput(''); setShadowPractice(null); }} className="w-12 h-12 flex items-center justify-center rounded-full border border-outline-variant hover:bg-surface-high transition-colors text-on-surface-variant">
                <span className="material-symbols-outlined">close</span>
              </button>
              <button type="button" onClick={toggleListening} disabled={loading && !listening} className="relative group disabled:opacity-60 disabled:cursor-not-allowed" aria-label={listening ? 'Stop recording and upload' : 'Start recording'}>
                {listening && <div className="absolute inset-0 bg-primary/20 rounded-full mic-pulse" />}
                <div className="relative w-20 h-20 flex items-center justify-center rounded-full bg-primary text-white shadow-xl hover:scale-105 active:scale-95 transition-transform">
                  <span className="material-symbols-outlined text-[32px]" style={{ fontVariationSettings: "'FILL' 1" }}>{listening ? 'stop' : 'mic'}</span>
                </div>
              </button>
              <button type="submit" disabled={loading || !input.trim()} className="w-12 h-12 flex items-center justify-center rounded-full bg-error-container text-error hover:bg-error/10 transition-colors disabled:opacity-50" aria-label="Send typed text fallback">
                <span className="material-symbols-outlined">send</span>
              </button>
            </div>
          </div>
        </form>
        </main>
      </Panel>

      <PanelResizeHandle className="hidden lg:block w-px bg-outline-variant/30 hover:bg-primary/40 active:bg-primary/60 transition-colors cursor-col-resize" />

      <Panel defaultSize={36} minSize={28} maxSize={55} order={3} className="hidden lg:block">
        <aside className="flex flex-col w-full h-full min-h-0 bg-surface border-l border-outline-variant/30 p-6 overflow-y-auto custom-scrollbar">
        <h2 className="text-2xl font-semibold mb-6">Live Feedback</h2>
        <div className="space-y-6">
          <CorrectionsPanel corrections={corrections} />
          <ShadowAnswer
            answer={lastResponse?.shadowAnswer ?? ''}
            active={Boolean(shadowPractice)}
            onPractice={startShadowPractice}
          />
          <section className="bg-tertiary-400/10 border border-tertiary/20 rounded-xl p-4">
            <div className="flex items-center gap-2 mb-3 text-tertiary">
              <span className="material-symbols-outlined text-[20px]">lightbulb</span>
              <h3 className="text-sm font-bold">Coach Insight</h3>
            </div>
            <p className="text-base leading-7 text-tertiary">
              {lastResponse?.strategy ? strategyText(lastResponse.strategy) : 'Speak in complete sentences. Take a short pause instead of using filler words.'}
            </p>
          </section>
          <FeedbackSpeech metrics={speechMetrics} userText={lastResponse?.userText ?? ''} />
          <FeedbackWordTimeline words={wordTimestamps} />
          {summary && (
            <section className="bg-white border border-outline-variant/20 rounded-xl p-4 shadow-sm">
              <div className="flex items-center gap-2 mb-3 text-primary">
                <span className="material-symbols-outlined text-[20px]">summarize</span>
                <h3 className="text-sm font-bold">Practice Summary</h3>
              </div>
              <p className="text-base leading-7 text-on-surface">{summary.next_focus || 'Review today’s corrections and repeat the shadow answer aloud.'}</p>
            </section>
          )}
          <div className="relative rounded-xl overflow-hidden aspect-video bg-primary-800 p-4 flex items-end">
            <div className="absolute inset-0 bg-gradient-to-br from-primary/40 via-secondary/20 to-tertiary/20" />
            <span className="relative text-white text-xs font-semibold">Focus Mode Active</span>
          </div>
        </div>
        </aside>
      </Panel>
    </PanelGroup>
  );
}

function Avatar({ icon, tone }: { icon: string; tone: 'ai' | 'user' }) {
  return (
    <div className={`w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 ${tone === 'ai' ? 'bg-surface-high text-primary' : 'bg-primary-100 text-primary-800'}`}>
      <span className="material-symbols-outlined" style={{ fontVariationSettings: tone === 'ai' ? "'FILL' 1" : undefined }}>{icon}</span>
    </div>
  );
}

function AbilityBar({ label, value, color }: { label: string; value: number; color: string }) {
  const safeValue = Math.max(0, Math.min(100, value));
  return (
    <div className="space-y-2">
      <div className="flex justify-between items-center">
        <span className="text-sm font-medium">{label}</span>
        <span className="text-xs font-semibold text-primary">{safeValue}%</span>
      </div>
      <div className="h-2 bg-outline-variant/20 rounded-full overflow-hidden">
        <div className={`${color} h-full rounded-full`} style={{ width: `${safeValue}%` }} />
      </div>
    </div>
  );
}

function FeedbackSpeech({ metrics, userText }: { metrics: SpeechMetrics | null; userText: string }) {
  const wpm = metrics?.words_per_minute ?? 0;
  const pauses = metrics?.pause_count ?? 0;
  const fluency = metrics?.fluency_score ?? 0;
  const pronunciation = metrics?.pronunciation_score ?? 0;
  const shadowMatch = normalizeNumber(metrics?.shadow_similarity_score);
  const isShadowing = metrics?.practice_mode === 'shadowing';
  return (
    <section className="bg-white border border-outline-variant/20 rounded-xl p-4 shadow-sm">
      <div className="flex items-center gap-2 mb-3 text-primary">
        <span className="material-symbols-outlined text-[20px]">graphic_eq</span>
        <h3 className="text-sm font-bold">Speech Metrics</h3>
      </div>
      <div className="grid grid-cols-2 gap-2 text-center">
        {isShadowing && <Metric label="Shadowing Match" value={shadowMatch !== null ? `${shadowMatch}%` : '—'} />}
        <Metric label="WPM" value={wpm ? String(wpm) : '—'} />
        <Metric label="Pauses" value={String(pauses)} />
        <Metric label="Fluency" value={fluency ? `${fluency}%` : '—'} />
        <Metric label="Pronunciation" value={pronunciation ? `${pronunciation}%` : '—'} />
      </div>
      {isShadowing && (
        <div className="mt-3 space-y-2 rounded-lg bg-primary-50/60 p-3">
          <div>
            <p className="text-[11px] font-bold uppercase text-primary">Target</p>
            <p className="text-xs leading-5 text-on-surface">{metrics?.practice_target || '—'}</p>
          </div>
          <div>
            <p className="text-[11px] font-bold uppercase text-primary">Your Transcript</p>
            <p className="text-xs leading-5 text-on-surface">{userText || '—'}</p>
          </div>
        </div>
      )}
      {metrics?.source && (
        <p className="mt-3 text-xs text-on-surface-variant">Source: {metrics.source}</p>
      )}
    </section>
  );
}

function FeedbackWordTimeline({ words }: { words: WordTimestamp[] }) {
  const preview = words.slice(0, 20);
  return (
    <section className="bg-white border border-outline-variant/20 rounded-xl p-4 shadow-sm">
      <div className="flex items-center gap-2 mb-3 text-secondary">
        <span className="material-symbols-outlined text-[20px]">timeline</span>
        <h3 className="text-sm font-bold">Word Timeline</h3>
      </div>
      {preview.length === 0 ? (
        <p className="text-sm leading-6 text-on-surface-variant">Word timestamps will appear after a voice turn.</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {preview.map((item, index) => (
            <span key={`${item.word}-${index}`} className="rounded-lg bg-secondary-400/10 px-2 py-1 text-xs font-semibold text-secondary">
              {item.word}
              <span className="ml-1 text-[10px] text-on-surface-variant">
                {formatSeconds(item.start)}-{formatSeconds(item.end)}s
              </span>
            </span>
          ))}
        </div>
      )}
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-surface-low px-2 py-3">
      <p className="text-base font-bold text-on-surface">{value}</p>
      <p className="text-[11px] font-semibold text-on-surface-variant mt-1">{label}</p>
    </div>
  );
}

function formatSeconds(value: number): string {
  return Number.isFinite(value) ? value.toFixed(1) : '0.0';
}

function normalizeCorrections(value: ChatResponse['corrections'] | undefined): Correction[] {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function normalizeAbility(value: ChatResponse['abilityScore'] | undefined): AbilityScore | null {
  if (!value) return null;
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value) as AbilityScore;
  } catch {
    return null;
  }
}

function normalizeSummary(value: ChatResponse['summary'] | undefined): SessionSummary | null {
  if (!value) return null;
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value) as SessionSummary;
  } catch {
    return null;
  }
}

function normalizeSpeechMetrics(value: ChatResponse['speechMetrics'] | undefined): SpeechMetrics | null {
  if (!value) return null;
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value) as SpeechMetrics;
  } catch {
    return null;
  }
}

function normalizeWordTimestamps(value: ChatResponse['wordTimestamps'] | undefined): WordTimestamp[] {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function normalizeNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function buildLocalSummary(turns: ChatTurn[]): SessionSummary {
  return {
    total_turns: turns.filter((t) => t.role === 'user').length,
    next_focus: 'Review corrections and repeat the shadow answers aloud.',
    highlights: [],
  };
}

function pickRecordingMimeType(): string {
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'];
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) ?? '';
}

function strategyText(strategy: string) {
  return {
    hint_question: 'The coach is giving a sentence pattern hint because this turn needs more support.',
    normal_follow_up: 'The coach is continuing with a natural follow-up question.',
    challenge_question: 'The coach is increasing difficulty because your answer is strong.',
    review_old_error: 'The coach is asking you to retry a recurring or high-severity error.',
  }[strategy] ?? 'The coach is adapting the next question to your current performance.';
}

function mapSendError(status: number | undefined): string {
  if (status === 401) return 'Please sign in again.';
  if (status === 404) return 'Session not found.';
  if (status && status >= 500) return 'AI service is unavailable, please try again.';
  return 'Something went wrong. Please try again.';
}
