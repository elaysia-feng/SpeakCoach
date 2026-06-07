import type { ButtonHTMLAttributes, ReactNode } from 'react';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  children: ReactNode;
}

// 可复用按钮，primary / secondary / ghost / danger 变体与设计系统保持一致。
export default function Button({
  variant = 'primary',
  className = '',
  children,
  ...rest
}: ButtonProps) {
  const base =
    'inline-flex items-center justify-center gap-2 font-medium rounded-lg px-4 py-2.5 transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed';
  const variants: Record<Variant, string> = {
    primary:
      'bg-primary text-white hover:bg-primary-700 shadow-soft hover:shadow-lift',
    secondary:
      'bg-white text-primary border border-primary hover:bg-primary-50',
    ghost:
      'bg-transparent text-on-surface-variant hover:bg-surface-low',
    danger:
      'bg-white text-error border border-error/30 hover:bg-error/5',
  };
  return (
    <button className={`${base} ${variants[variant]} ${className}`} {...rest}>
      {children}
    </button>
  );
}
