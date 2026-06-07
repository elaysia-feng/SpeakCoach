import { useEffect, useState } from 'react';
import { api } from '../lib/api';

interface Props {
  src: string;
}

function toApiPath(src: string): string {
  if (src.startsWith('/api/')) {
    return src.slice('/api'.length);
  }
  try {
    const url = new URL(src);
    return url.pathname.startsWith('/api/') ? url.pathname.slice('/api'.length) : src;
  } catch {
    return src;
  }
}

function isBlobUrl(src: string): boolean {
  return src.startsWith('blob:');
}

function isExternalUrl(src: string): boolean {
  try {
    const url = new URL(src);
    return url.origin !== window.location.origin && !url.pathname.startsWith('/api/');
  } catch {
    return false;
  }
}

export default function AudioPlayer({ src }: Props) {
  const [objectUrl, setObjectUrl] = useState<string>('');
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!src) {
      setObjectUrl('');
      setError(false);
      return;
    }
    let cancelled = false;
    let nextUrl = '';
    setError(false);
    // blob: URL 是浏览器本地的对象 URL, 直接用 src 即可, 不应再走 api.get<Blob> 流程
    // (之前没识别, 会被 axios 当成相对路径拼上 /api/ 前缀, 出现 /api/blob:http://... 这种畸形请求)
    if (isBlobUrl(src) || isExternalUrl(src)) {
      setObjectUrl(src);
      return () => {
        cancelled = true;
      };
    }
    api.get<Blob>(toApiPath(src), { responseType: 'blob' })
      .then(({ data }) => {
        if (cancelled) return;
        nextUrl = URL.createObjectURL(data);
        setObjectUrl(nextUrl);
      })
      .catch(() => {
        if (!cancelled) {
          setError(true);
          setObjectUrl('');
        }
      });
    return () => {
      cancelled = true;
      if (nextUrl) {
        URL.revokeObjectURL(nextUrl);
      }
    };
  }, [src]);

  if (!src) {
    return (
      <div className="mt-2 ml-12 rounded-xl bg-surface-low border border-outline-variant/30 px-4 py-3 text-sm text-on-surface-variant">
        Audio is not available for this turn.
      </div>
    );
  }
  if (error) {
    return (
      <div className="mt-2 ml-12 rounded-xl bg-error-container/40 border border-error/30 px-4 py-3 text-sm text-error">
        Audio could not be loaded.
      </div>
    );
  }
  return (
    // 不再用 ml-12 (原本是给 AI 气泡让 avatar 位), 改为 mt-2 + max-w-md,
    // 这样用户气泡 (无文字只剩播放器) 不会变成"蓝色大方块套小白播放器"
    <div className="mt-2 rounded-xl bg-white border border-outline-variant/30 p-3 shadow-soft max-w-md">
      {objectUrl ? <audio controls src={objectUrl} className="w-full h-10" /> : <div className="text-sm text-on-surface-variant">Loading audio...</div>}
    </div>
  );
}
