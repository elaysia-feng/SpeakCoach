// Sparkline —— 纯 SVG 折线图，给 Profile 页能力趋势用。
// 4 条线对应 grammar / vocabulary / fluency / logic。
// 入参 points 是按时间顺序排列的 AbilityHistoryEntry 列表。
// 当样本数 < 2 时退化为"无趋势"占位，避免无意义的 0 长度路径。
import type { AbilityHistoryEntry } from '../lib/types';

interface Props {
  entries: AbilityHistoryEntry[];
  width?: number;
  height?: number;
  className?: string;
}

interface SeriesConfig {
  key: 'grammarScore' | 'vocabularyScore' | 'fluencyScore' | 'logicScore';
  color: string;
  label: string;
}

const SERIES: SeriesConfig[] = [
  { key: 'grammarScore', color: '#004ac6', label: 'Grammar' },
  { key: 'vocabularyScore', color: '#10b981', label: 'Vocab' },
  { key: 'fluencyScore', color: '#f59e0b', label: 'Fluency' },
  { key: 'logicScore', color: '#ef4444', label: 'Logic' },
];

// 把 [0,100] 映射到 SVG y 坐标。
function toY(value: number, height: number, padding: number): number {
  const clamped = Math.max(0, Math.min(100, value));
  return padding + (1 - clamped / 100) * (height - 2 * padding);
}

export default function Sparkline({
  entries,
  width = 320,
  height = 120,
  className = '',
}: Props) {
  const padding = 8;
  const validEntries = entries
    .map((entry) => {
      const values = SERIES.map((s) => entry[s.key] ?? null);
      return { values };
    })
    .filter(({ values }) => values.some((v) => v !== null));

  if (validEntries.length < 2) {
    return (
      <div
        className={`flex items-center justify-center text-sm text-on-surface-variant bg-surface-low border border-outline-variant/30 rounded-lg ${className}`}
        style={{ width, height }}
      >
        {validEntries.length === 0
          ? 'No history yet — finish a session to see your trend.'
          : 'Need at least 2 sessions to draw a trend.'}
      </div>
    );
  }

  // 时间顺序：x 坐标 0..(n-1)
  const n = validEntries.length;
  const stepX = (width - 2 * padding) / (n - 1);

  return (
    <svg
      className={className}
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label="Ability score trend"
    >
      {/* 横向网格线：25/50/75/100 */}
      {[25, 50, 75, 100].map((tick) => (
        <line
          key={tick}
          x1={padding}
          x2={width - padding}
          y1={toY(tick, height, padding)}
          y2={toY(tick, height, padding)}
          stroke="#e5e7eb"
          strokeWidth={1}
          strokeDasharray="3 3"
        />
      ))}

      {/* 数据线 */}
      {SERIES.map((series) => {
        const points = validEntries
          .map(({ values }, idx) => {
            const seriesIdx = SERIES.findIndex((s) => s.key === series.key);
            const v = values[seriesIdx];
            if (v === null) return null;
            return {
              x: padding + idx * stepX,
              y: toY(v, height, padding),
            };
          })
          .filter((p): p is { x: number; y: number } => p !== null);
        if (points.length < 2) return null;
        const d = points
          .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`)
          .join(' ');
        return (
          <path
            key={series.key}
            d={d}
            fill="none"
            stroke={series.color}
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        );
      })}

      {/* 数据点 */}
      {SERIES.map((series) => {
        const seriesIdx = SERIES.findIndex((s) => s.key === series.key);
        return validEntries.map(({ values }, idx) => {
          const v = values[seriesIdx];
          if (v === null) return null;
          const x = padding + idx * stepX;
          const y = toY(v, height, padding);
          return (
            <circle
              key={`${series.key}-${idx}`}
              cx={x}
              cy={y}
              r={2.5}
              fill={series.color}
            />
          );
        });
      })}

      {/* 图例 —— 渲染在底部 */}
      <g transform={`translate(${padding}, ${height - padding - 2})`}>
        {SERIES.map((series, i) => (
          <g key={series.key} transform={`translate(${i * 70}, 0)`}>
            <rect width={8} height={8} fill={series.color} rx={2} />
            <text x={12} y={8} fontSize={10} fill="#475569">
              {series.label}
            </text>
          </g>
        ))}
      </g>
    </svg>
  );
}
