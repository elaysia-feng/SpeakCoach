/** @type {import('tailwindcss').Config} */
// Tailwind 配置 —— 颜色取自 front/stitch/speakcoach_design_system/DESIGN.md
// （专业蓝 / 认知紫 / 成功绿 + 中性灰阶）
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 品牌色
        primary: {
          DEFAULT: '#004ac6',
          50: '#eef3ff',
          100: '#dbe1ff',
          200: '#b4c5ff',
          300: '#7c98ff',
          400: '#2563eb',
          500: '#0053db',
          600: '#004ac6',
          700: '#003ea8',
          800: '#00174b',
          900: '#000f33',
        },
        secondary: {
          DEFAULT: '#712ae2',
          400: '#8a4cfc',
          500: '#712ae2',
          600: '#5a00c6',
        },
        tertiary: {
          DEFAULT: '#006242',
          400: '#007d55',
          500: '#10b981',
          600: '#006242',
        },
        background: '#f8f9ff',
        'on-surface': '#0b1c30',
        'on-surface-variant': '#434655',
        'on-primary': '#ffffff',
        'on-primary-container': '#eeefff',
        'on-error-container': '#93000a',
        'error-container': '#ffdad6',
        // 表面（Material 3 色调系统）
        surface: {
          DEFAULT: '#f8f9ff',
          dim: '#cbdbf5',
          bright: '#f8f9ff',
          lowest: '#ffffff',
          low: '#eff4ff',
          container: '#e5eeff',
          high: '#dce9ff',
          highest: '#d3e4fe',
          variant: '#d3e4fe',
        },
        outline: {
          DEFAULT: '#737686',
          variant: '#c3c6d7',
        },
        // 语义色
        error: '#ba1a1a',
        success: '#10b981',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        display: ['Inter', 'system-ui', 'sans-serif'],
      },
      borderRadius: {
        sm: '0.25rem',
        DEFAULT: '0.5rem',
        md: '0.75rem',
        lg: '1rem',
        xl: '1.5rem',
      },
      boxShadow: {
        soft: '0 4px 20px rgba(0, 0, 0, 0.05)',
        lift: '0 10px 30px rgba(0, 0, 0, 0.08)',
      },
      maxWidth: {
        container: '1280px',
      },
    },
  },
  plugins: [],
};
