import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, consumePostLoginRedirect } from '../lib/api';
import { setToken, setCurrentUser } from '../lib/auth';
import Button from '../components/Button';
import type { AuthResponse } from '../lib/types';

// 认证模式：
//   login-password —— 使用用户名/邮箱 + 密码登录
//   login-code     —— 使用邮箱 + 6 位验证码登录（通过 /send-code type=login 发送）
//   register-1     —— 注册步骤 1：输入邮箱，请求验证码
//   register-2     —— 注册步骤 2：输入 6 位验证码，获取 verifyToken
//   register-3     —— 注册步骤 3：填写用户名 + 密码，完成注册
type Mode = 'login-password' | 'login-code' | 'register-1' | 'register-2' | 'register-3';

const RESEND_COOLDOWN_SECONDS = 60;

export default function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login-password');

  // 共享表单状态
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [identifier, setIdentifier] = useState('');

  // 流程状态
  const [verifyToken, setVerifyToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [resendIn, setResendIn] = useState(0);

  const codeInputRef = useRef<HTMLInputElement | null>(null);

  // 切换模式时重置所有状态。
  useEffect(() => {
    setError(null);
    setInfo(null);
  }, [mode]);

  // 重新发送验证码的倒计时。
  useEffect(() => {
    if (resendIn <= 0) return;
    const t = setTimeout(() => setResendIn((s) => Math.max(0, s - 1)), 1000);
    return () => clearTimeout(t);
  }, [resendIn]);

  // 在 register-2 / login-code 时自动聚焦 6 位验证码输入框。
  useEffect(() => {
    if (mode === 'register-2' || mode === 'login-code') {
      codeInputRef.current?.focus();
    }
  }, [mode]);

  const afterAuth = (data: AuthResponse) => {
    setToken(data.token);
    setCurrentUser({ userId: data.userId, username: data.username, email: data.email });
    navigate(consumePostLoginRedirect('/dashboard'), { replace: true });
  };

  const mapErr = (err: unknown, fallback: string) => {
    const e = err as { response?: { status?: number; data?: { message?: string } } };
    const status = e?.response?.status;
    const msg = e?.response?.data?.message;
    if (msg) return msg;
    if (status === 401) return 'Invalid credentials.';
    if (status === 400) return 'Invalid input. Please check your details.';
    if (status === 429) return 'Too many requests. Please wait a moment.';
    if (status === 502 || status === 503) return 'Email service is temporarily unavailable. Please try again later.';
    return fallback;
  };

  const sendCode = async (forMode: 'register' | 'login'): Promise<boolean> => {
    if (!email.trim()) {
      setError('Please enter your email address.');
      return false;
    }
    setError(null);
    setInfo(null);
    setSending(true);
    try {
      await api.post('/auth/send-code', { email: email.trim(), type: forMode });
      setInfo(`A 6-digit code has been sent to ${email.trim()}. Please check your inbox.`);
      setResendIn(RESEND_COOLDOWN_SECONDS);
      setCode('');
      return true;
    } catch (err) {
      setError(mapErr(err, 'Failed to send verification code.'));
      return false;
    } finally {
      setSending(false);
    }
  };

  // === 提交处理器 =====================================================

  const submitLoginPassword = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const value = identifier.trim();
      const looksLikeEmail = value.includes('@');
      const payload = {
        username: looksLikeEmail ? '' : value,
        email: looksLikeEmail ? value : '',
        password,
      };
      const { data } = await api.post<AuthResponse>('/auth/login', payload);
      afterAuth(data);
    } catch (err) {
      setError(mapErr(err, 'Sign in failed. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  const submitLoginCode = async (e: FormEvent) => {
    e.preventDefault();
    if (code.length !== 6) {
      setError('Please enter the 6-digit code from your email.');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const { data } = await api.post<AuthResponse>('/auth/login-by-code', {
        email: email.trim(),
        code: code.trim(),
      });
      afterAuth(data);
    } catch (err) {
      setError(mapErr(err, 'Code sign in failed. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  const submitRegisterEmail = async (e: FormEvent) => {
    e.preventDefault();
    const sent = await sendCode('register');
    // 如果发送成功，前进到验证码步骤。
    if (sent) {
      setMode((m) => (m === 'register-1' ? 'register-2' : m));
    }
  };

  const submitRegisterCode = async (e: FormEvent) => {
    e.preventDefault();
    if (code.length !== 6) {
      setError('Please enter the 6-digit code.');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const { data } = await api.post<{ verifyToken: string }>('/auth/verify-code', {
        email: email.trim(),
        code: code.trim(),
      });
      setVerifyToken(data.verifyToken);
      setMode('register-3');
    } catch (err) {
      setError(mapErr(err, 'Verification failed. The code may be expired or incorrect.'));
    } finally {
      setLoading(false);
    }
  };

  const submitRegisterFinish = async (e: FormEvent) => {
    e.preventDefault();
    if (!verifyToken) {
      setError('Your verification session has expired. Please restart the registration.');
      setMode('register-1');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const { data } = await api.post<AuthResponse>('/auth/set-password', {
        verifyToken,
        username: username.trim(),
        password,
      });
      afterAuth(data);
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 401 || status === 400) {
        setError('Your verification token has expired. Please restart the registration.');
        setMode('register-1');
        return;
      }
      setError(mapErr(err, 'Could not complete registration.'));
    } finally {
      setLoading(false);
    }
  };

  // === 共享子组件 ===============================================

  const ErrorBanner = () =>
    error ? (
      <div role="alert" className="flex items-start gap-3 bg-error-container/40 border border-error/30 text-error rounded-xl px-4 py-3">
        <span className="material-symbols-outlined text-[20px] mt-0.5">error</span>
        <p className="flex-1 text-sm leading-6">{error}</p>
        <button type="button" onClick={() => setError(null)} className="text-error/80 hover:text-error" aria-label="Dismiss error">
          <span className="material-symbols-outlined text-[18px]">close</span>
        </button>
      </div>
    ) : null;

  const InfoBanner = () =>
    info ? (
      <div role="status" className="flex items-start gap-3 bg-primary/10 border border-primary/30 text-primary rounded-xl px-4 py-3">
        <span className="material-symbols-outlined text-[20px] mt-0.5">mark_email_read</span>
        <p className="flex-1 text-sm leading-6">{info}</p>
      </div>
    ) : null;

  // === 视图片段 =====================================================

  const titleAndSubtitle = () => {
    if (mode === 'login-password') return { title: 'Welcome back', sub: 'Sign in to continue your practice.' };
    if (mode === 'login-code') return { title: 'Sign in with code', sub: `Enter the 6-digit code we sent to ${email || 'your email'}.` };
    if (mode === 'register-1') return { title: 'Create your account', sub: 'Start practising spoken English in minutes. We will email you a verification code.' };
    if (mode === 'register-2') return { title: 'Check your email', sub: `We sent a 6-digit code to ${email || 'your email'}. Enter it below.` };
    return { title: 'Set your credentials', sub: 'Almost done. Pick a username and password to finish.' };
  };

  const { title, sub } = titleAndSubtitle();

  const registerStepIndex = mode.startsWith('register') ? Number(mode.slice(-1)) : 0;
  const StepDots = () => (
    <div className="flex items-center gap-2 mb-6" aria-label={`Step ${registerStepIndex} of 3`}>
      {[1, 2, 3].map((n) => (
        <div
          key={n}
          className={`h-1.5 flex-1 rounded-full transition-colors ${
            n <= registerStepIndex ? 'bg-primary' : 'bg-outline-variant/40'
          }`}
        />
      ))}
    </div>
  );

  // === 表单内容 ========================================================

  const renderLoginPassword = () => (
    <form onSubmit={submitLoginPassword} className="space-y-6">
      <div>
        <label htmlFor="identifier" className="block text-sm font-medium text-on-surface-variant mb-2">Username or Email</label>
        <input id="identifier" type="text" required autoComplete="username" value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
          placeholder="name@company.com" />
      </div>
      <div>
        <label htmlFor="password" className="block text-sm font-medium text-on-surface-variant mb-2">Password</label>
        <input id="password" type="password" required minLength={6} maxLength={128} autoComplete="current-password" value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
          placeholder="••••••••" />
      </div>
      <ErrorBanner />
      <Button type="submit" disabled={loading} className="w-full py-4 rounded-xl shadow-lg shadow-primary/20">
        {loading ? 'Please wait…' : 'Sign in'}
        <span className="material-symbols-outlined text-[20px]">login</span>
      </Button>
      <div className="flex items-center justify-between text-sm">
        <button type="button" onClick={() => setMode('login-code')}
          className="text-primary font-medium hover:underline">Use email code instead</button>
      </div>
    </form>
  );

  const renderLoginCode = () => (
    <form onSubmit={submitLoginCode} className="space-y-6">
      <div>
        <label htmlFor="login-email" className="block text-sm font-medium text-on-surface-variant mb-2">Email</label>
        <input id="login-email" type="email" required autoComplete="email" value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
          placeholder="name@company.com" />
      </div>
      <div className="flex items-end gap-3">
        <div className="flex-1">
          <label htmlFor="login-code" className="block text-sm font-medium text-on-surface-variant mb-2">6-digit code</label>
          <input id="login-code" ref={codeInputRef} type="text" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all tracking-[0.4em] text-center text-lg"
            placeholder="000000" />
        </div>
        <Button type="button" variant="secondary" onClick={() => sendCode('login')} disabled={sending || resendIn > 0}
          className="h-[52px] px-4 rounded-xl whitespace-nowrap">
          {sending ? 'Sending…' : resendIn > 0 ? `Resend (${resendIn}s)` : 'Send code'}
        </Button>
      </div>
      <ErrorBanner /><InfoBanner />
      <Button type="submit" disabled={loading} className="w-full py-4 rounded-xl shadow-lg shadow-primary/20">
        {loading ? 'Please wait…' : 'Sign in'}
        <span className="material-symbols-outlined text-[20px]">login</span>
      </Button>
      <div className="flex items-center justify-between text-sm">
        <button type="button" onClick={() => setMode('login-password')}
          className="text-primary font-medium hover:underline">Use password instead</button>
      </div>
    </form>
  );

  const renderRegister1 = () => (
    <form onSubmit={submitRegisterEmail} className="space-y-6">
      <div>
        <label htmlFor="reg-email" className="block text-sm font-medium text-on-surface-variant mb-2">Email address</label>
        <input id="reg-email" type="email" required autoComplete="email" value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
          placeholder="name@company.com" />
      </div>
      <ErrorBanner />
      <Button type="submit" disabled={sending} className="w-full py-4 rounded-xl shadow-lg shadow-primary/20">
        {sending ? 'Sending…' : 'Send verification code'}
        <span className="material-symbols-outlined text-[20px]">mail</span>
      </Button>
    </form>
  );

  const renderRegister2 = () => (
    <form onSubmit={submitRegisterCode} className="space-y-6">
      <div>
        <label htmlFor="reg-code" className="block text-sm font-medium text-on-surface-variant mb-2">6-digit code</label>
        <input id="reg-code" ref={codeInputRef} type="text" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all tracking-[0.4em] text-center text-2xl"
          placeholder="000000" />
      </div>
      <div className="flex items-center justify-between text-sm">
        <button type="button" onClick={() => sendCode('register')} disabled={sending || resendIn > 0}
          className="text-primary font-medium hover:underline disabled:text-on-surface-variant disabled:no-underline disabled:cursor-not-allowed">
          {resendIn > 0 ? `Resend code in ${resendIn}s` : 'Resend code'}
        </button>
        <button type="button" onClick={() => { setMode('register-1'); setCode(''); }}
          className="text-on-surface-variant hover:text-on-surface">Change email</button>
      </div>
      <ErrorBanner /><InfoBanner />
      <Button type="submit" disabled={loading} className="w-full py-4 rounded-xl shadow-lg shadow-primary/20">
        {loading ? 'Verifying…' : 'Verify code'}
        <span className="material-symbols-outlined text-[20px]">verified</span>
      </Button>
    </form>
  );

  const renderRegister3 = () => (
    <form onSubmit={submitRegisterFinish} className="space-y-6">
      <div>
        <label htmlFor="reg-username" className="block text-sm font-medium text-on-surface-variant mb-2">Username</label>
        <input id="reg-username" type="text" required minLength={3} maxLength={64} autoComplete="username" value={username}
          onChange={(e) => setUsername(e.target.value)}
          className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
          placeholder="yourname" />
      </div>
      <div>
        <label htmlFor="reg-password" className="block text-sm font-medium text-on-surface-variant mb-2">Password</label>
        <input id="reg-password" type="password" required minLength={6} maxLength={128} autoComplete="new-password" value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="w-full px-4 py-3.5 bg-surface-lowest border border-outline-variant rounded-xl text-on-surface placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
          placeholder="At least 6 characters" />
      </div>
      <ErrorBanner />
      <Button type="submit" disabled={loading} className="w-full py-4 rounded-xl shadow-lg shadow-primary/20">
        {loading ? 'Creating account…' : 'Create account & sign in'}
        <span className="material-symbols-outlined text-[20px]">how_to_reg</span>
      </Button>
    </form>
  );

  const renderModeBody = () => {
    switch (mode) {
      case 'login-password': return renderLoginPassword();
      case 'login-code': return renderLoginCode();
      case 'register-1': return renderRegister1();
      case 'register-2': return renderRegister2();
      case 'register-3': return renderRegister3();
    }
  };

  const isRegister = mode.startsWith('register');

  return (
    <div className="flex-1 min-h-screen flex bg-background overflow-hidden">
      {/* 营销面板 */}
      <section className="hidden md:flex md:w-1/2 bg-surface-low relative flex-col justify-center px-20 overflow-hidden">
        <div className="absolute inset-0 opacity-10 pointer-events-none">
          <div className="absolute -top-24 -right-24 w-[500px] h-[500px] bg-primary rounded-full blur-[120px]" />
          <div className="absolute -bottom-24 -left-24 w-[400px] h-[400px] bg-secondary rounded-full blur-[100px]" />
        </div>
        <div className="relative z-10">
          <div className="flex items-center gap-3 mb-12">
            <div className="w-12 h-12 bg-primary rounded-xl flex items-center justify-center shadow-lg">
              <span className="material-symbols-outlined text-white text-3xl" style={{ fontVariationSettings: "'FILL' 1" }}>mic</span>
            </div>
            <span className="text-2xl font-bold text-primary">SpeakCoach Agent</span>
          </div>
          <h1 className="text-5xl font-bold text-on-surface mb-4 leading-tight">Master Spoken English with AI</h1>
          <p className="text-lg leading-8 text-on-surface-variant mb-12 max-w-md">
            The elite coaching platform for professionals looking to refine their communication skills with real-time artificial intelligence.
          </p>
          <div className="space-y-6">
            {[
              ['auto_awesome', 'Real-time feedback'],
              ['work', 'Industry-specific scenarios'],
              ['monitoring', 'Personal progress tracking'],
            ].map(([icon, label]) => (
              <div key={label} className="flex items-center gap-4">
                <div className="w-10 h-10 rounded-full bg-surface-high flex items-center justify-center">
                  <span className="material-symbols-outlined text-primary">{icon}</span>
                </div>
                <span className="text-base font-medium text-on-surface">{label}</span>
              </div>
            ))}
          </div>
        </div>
        <div className="relative z-10 mt-20 animate-[float_6s_ease-in-out_infinite]">
          <div className="w-full max-w-lg rounded-2xl shadow-2xl border border-white/40 bg-white/80 p-6">
            <div className="flex items-center justify-between mb-6">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wide text-outline">Voice Analysis</p>
                <p className="text-2xl font-semibold text-on-surface">Session Readiness</p>
              </div>
              <span className="material-symbols-outlined text-primary text-4xl">graphic_eq</span>
            </div>
            <div className="flex items-end gap-2 h-24 mb-6">
              {[35, 58, 42, 76, 64, 88, 54, 70, 46, 82].map((h, index) => (
                <div key={index} className="flex-1 rounded-full bg-primary/70" style={{ height: `${h}%` }} />
              ))}
            </div>
            <div className="grid grid-cols-3 gap-3 text-sm">
              <div className="rounded-xl bg-surface-low p-3"><p className="text-outline">Grammar</p><p className="font-bold text-primary">82%</p></div>
              <div className="rounded-xl bg-surface-low p-3"><p className="text-outline">Fluency</p><p className="font-bold text-secondary">76%</p></div>
              <div className="rounded-xl bg-surface-low p-3"><p className="text-outline">Logic</p><p className="font-bold text-tertiary">88%</p></div>
            </div>
          </div>
        </div>
      </section>

      {/* 表单面板 */}
      <section className="w-full md:w-1/2 flex items-center justify-center px-4 md:px-10 py-10 bg-white relative">
        <div className="md:hidden absolute top-8 left-4 flex items-center gap-2">
          <div className="w-8 h-8 bg-primary rounded-lg flex items-center justify-center">
            <span className="material-symbols-outlined text-white text-xl" style={{ fontVariationSettings: "'FILL' 1" }}>mic</span>
          </div>
          <span className="text-2xl font-bold text-primary">SpeakCoach</span>
        </div>
        <div className="w-full max-w-[440px]">
          <div className="mb-8">
            <h2 className="text-3xl font-semibold text-on-surface mb-2">{title}</h2>
            <p className="text-base leading-7 text-on-surface-variant">{sub}</p>
          </div>

          {isRegister && <StepDots />}

          {renderModeBody()}

          <p className="mt-6 text-center text-sm text-on-surface-variant">
            {mode === 'login-password' || mode === 'login-code' ? (
              <>Don't have an account?{' '}
                <button type="button" onClick={() => { setMode('register-1'); setCode(''); setPassword(''); setVerifyToken(null); }}
                  className="text-primary font-semibold hover:underline">Create one</button>
              </>
            ) : (
              <>Already have an account?{' '}
                <button type="button" onClick={() => { setMode('login-password'); setCode(''); setVerifyToken(null); }}
                  className="text-primary font-semibold hover:underline">Sign in</button>
              </>
            )}
          </p>
        </div>
      </section>
    </div>
  );
}
