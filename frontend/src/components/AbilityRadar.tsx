// AbilityRadar —— 4 维能力雷达图（纯 SVG，无第三方库）。
// 期望值是 0-100 的归一化分数。

import type { CSSProperties } from 'react';

interface AbilityRadarProps {
  values: { grammar: number; vocabulary: number; fluency: number; logic: number };
  size?: number;
  className?: string;
  style?: CSSProperties;
  // 各维度对应的描边颜色（可选），缺省走 design token。
  colors?: { grammar: string; vocabulary: string; fluency: string; logic: string };
}

const DEFAULT_COLORS = {
  grammar: '#004ac6',   // primary
  vocabulary: '#712ae2', // secondary
  fluency: '#006242',    // tertiary
  logic: '#ba1a1a',      // error / accent
};

const LABELS: Record<keyof AbilityRadarProps['values'], string> = {
  grammar: 'Grammar',
  vocabulary: 'Vocabulary',
  fluency: 'Fluency',
  logic: 'Logic',
};

const AXES: Array<keyof AbilityRadarProps['values']> = ['grammar', 'vocabulary', 'fluency', 'logic'];

// 角度顺时针：上 / 右 / 下 / 左
const ANGLES: Record<keyof AbilityRadarProps['values'], number> = {
  grammar: -Math.PI / 2,
  vocabulary: 0,
  fluency: Math.PI / 2,
  logic: Math.PI,
};

export default function AbilityRadar({
  values,
  size = 240,
  className,
  style,
  colors = DEFAULT_COLORS,
}: AbilityRadarProps) {
  const cx = size / 2;
  const cy = size / 2;
  const radius = size * 0.35;
  const rings = [0.25, 0.5, 0.75, 1];

  // 构造 4 个数据点（语法/词汇/流利度/逻辑）。
  const points = AXES.map((key) => {
    const ratio = clamp01((values[key] ?? 0) / 100);
    const angle = ANGLES[key];
    return {
      key,
      x: cx + Math.cos(angle) * radius * ratio,
      y: cy + Math.sin(angle) * radius * ratio,
    };
  });

  const polygon = points.map((p) => `${p.x},${p.y}`).join(' ');

  return (
    <svg
      viewBox={`0 0 ${size} ${size}`}
      width={size}
      height={size}
      className={className}
      style={style}
      role="img"
      aria-label="Ability radar"
    >
      {/* 背景网格 */}
      {rings.map((r) => (
        <circle
          key={r}
          cx={cx}
          cy={cy}
          r={radius * r}
          fill="none"
          stroke="#c3c6d7"
          strokeOpacity={0.4}
          strokeWidth={1}
        />
      ))}
      {/* 4 条轴线 */}
      {AXES.map((key) => {
        const angle = ANGLES[key];
        const x2 = cx + Math.cos(angle) * radius;
        const y2 = cy + Math.sin(angle) * radius;
        return (
          <line
            key={key}
            x1={cx}
            y1={cy}
            x2={x2}
            y2={y2}
            stroke="#c3c6d7"
            strokeOpacity={0.4}
            strokeWidth={1}
          />
        );
      })}
      {/* 数据多边形 */}
      <polygon
        points={polygon}
        fill="#004ac6"
        fillOpacity={0.18}
        stroke="#004ac6"
        strokeWidth={2}
      />
      {/* 数据点 */}
      {points.map((p) => (
        <circle
          key={p.key}
          cx={p.x}
          cy={p.y}
          r={4}
          fill={colors[p.key]}
          stroke="#ffffff"
          strokeWidth={1.5}
        />
      ))}
      {/* 标签 + 数值 */}
      {AXES.map((key) => {
        const angle = ANGLES[key];
        const labelR = radius + 22;
        const x = cx + Math.cos(angle) * labelR;
        const y = cy + Math.sin(angle) * labelR;
        const value = Math.round(clamp01((values[key] ?? 0) / 100) * 100);
        return (
          <g key={key}>
            <text
              x={x}
              y={y - 4}
              textAnchor="middle"
              dominantBaseline="central"
              fontSize={12}
              fontWeight={600}
              fill="#0b1c30"
            >
              {LABELS[key]}
            </text>
            <text
              x={x}
              y={y + 10}
              textAnchor="middle"
              dominantBaseline="central"
              fontSize={11}
              fontWeight={500}
              fill={colors[key]}
            >
              {value}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

function clamp01(v: number): number {
  if (Number.isNaN(v)) return 0;
  return Math.max(0, Math.min(1, v));
}
