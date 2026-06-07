import { useEffect, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { api } from '../lib/api';
import { clearAuth, getToken, setCurrentUser } from '../lib/auth';
import type { User } from '../lib/types';

interface Props {
  children: ReactNode;
}

type ApiEnvelope<T> = { data: T };

function unwrapUser(data: User | ApiEnvelope<User>): User {
  return 'userId' in data ? data : data.data;
}

// ProtectedRoute —— 没有有效 token 时重定向到 /login；保留原本想访问的路径。
export default function ProtectedRoute({ children }: Props) {
  const location = useLocation();
  const [checking, setChecking] = useState(() => !!getToken());
  const [valid, setValid] = useState(() => !getToken());

  useEffect(() => {
    if (!getToken()) {
      setChecking(false);
      setValid(false);
      return;
    }
    let cancelled = false;
    setChecking(true);
    api.get<User | ApiEnvelope<User>>('/auth/me')
      .then(({ data }) => {
        if (cancelled) return;
        setCurrentUser(unwrapUser(data));
        setValid(true);
      })
      .catch(() => {
        if (cancelled) return;
        clearAuth();
        setValid(false);
      })
      .finally(() => {
        if (!cancelled) {
          setChecking(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [location.pathname]);

  if (!getToken()) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  if (checking) {
    return null;
  }
  if (!valid) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <>{children}</>;
}
