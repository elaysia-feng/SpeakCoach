import { Link, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import Button from './Button';

// Layout —— 固定头部 + 内容插槽。在 /login 上不渲染额外内容（头部仅显示品牌）。
export default function Layout() {
  const { user, logout } = useAuth();
  const location = useLocation();
  const onLogin = location.pathname === '/login';
  const navClass = (path: string) =>
    `text-sm h-full flex items-center transition-colors ${
      location.pathname === path
        ? 'font-bold text-primary border-b-2 border-primary'
        : 'font-medium text-on-surface-variant hover:text-primary'
    }`;

  return (
    <div className="min-h-full flex flex-col bg-surface text-on-surface">
      {!onLogin && (
      <header className="sticky top-0 z-40 bg-surface/80 backdrop-blur-md border-b border-outline-variant/30 shadow-sm">
        <div className="mx-auto max-w-container flex items-center justify-between px-4 md:px-10 h-16">
          <Link to={user ? '/dashboard' : '/login'} className="flex items-center gap-2">
            <div className="w-9 h-9 rounded-lg bg-primary flex items-center justify-center text-white">
              <span className="material-symbols-outlined" style={{ fontVariationSettings: "'FILL' 1" }}>
                mic
              </span>
            </div>
            <span className="font-bold text-xl text-primary">SpeakCoach</span>
          </Link>
          {!onLogin && user && (
            <div className="flex items-center gap-4">
              <nav className="hidden md:flex gap-8 items-center h-16">
                <Link
                  to="/dashboard"
                  className={navClass('/dashboard')}
                >
                  Dashboard
                </Link>
                <Link
                  to="/dashboard#history-section"
                  className="text-sm font-medium text-on-surface-variant hover:text-primary transition-colors"
                >
                  History
                </Link>
                <Link
                  to="/error-book"
                  className={navClass('/error-book')}
                >
                  Error Book
                </Link>
                <Link
                  to="/profile"
                  className={navClass('/profile')}
                >
                  Profile
                </Link>
                <Link
                  to="/settings"
                  className={navClass('/settings')}
                >
                  Settings
                </Link>
                <Link
                  to="/resources"
                  className={navClass('/resources')}
                >
                  Resources
                </Link>
              </nav>
              <Link
                to="/dashboard?new=1"
                className="hidden sm:inline-flex items-center justify-center gap-2 font-medium rounded-lg px-4 py-2.5 bg-white text-primary border border-primary hover:bg-primary-50 transition-all active:scale-[0.98]"
              >
                <span className="material-symbols-outlined text-[20px]">add</span>
                New
              </Link>
              <span className="hidden sm:inline text-sm text-on-surface-variant">{user.username}</span>
              <Button variant="ghost" onClick={logout}>
                <span className="material-symbols-outlined text-[20px]">logout</span>
              </Button>
            </div>
          )}
        </div>
      </header>
      )}
      <main className="flex-1 flex flex-col">
        <Outlet />
      </main>
    </div>
  );
}
