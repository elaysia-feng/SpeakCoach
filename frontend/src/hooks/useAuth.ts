import { useCallback, useEffect, useState } from 'react';
import { AUTH_CHANGE_EVENT, getCurrentUser, getToken, clearAuth } from '../lib/auth';
import type { User } from '../lib/types';

// useAuth —— 暴露当前用户和登出辅助函数。
// 在获得焦点时重新读取 localStorage，使多标签页登出更加及时。
export function useAuth(): {
  user: User | null;
  isAuthenticated: boolean;
  logout: () => void;
} {
  const [user, setUser] = useState<User | null>(() => getCurrentUser());

  useEffect(() => {
    const sync = () => setUser(getCurrentUser());
    window.addEventListener('focus', sync);
    window.addEventListener('storage', sync);
    window.addEventListener(AUTH_CHANGE_EVENT, sync);
    return () => {
      window.removeEventListener('focus', sync);
      window.removeEventListener('storage', sync);
      window.removeEventListener(AUTH_CHANGE_EVENT, sync);
    };
  }, []);

  const logout = useCallback(() => {
    clearAuth();
    setUser(null);
    window.location.assign('/login');
  }, []);

  return {
    user,
    isAuthenticated: !!user && !!getToken(),
    logout,
  };
}
