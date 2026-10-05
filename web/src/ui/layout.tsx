import type { CSSProperties, ElementType, ReactNode } from 'react';
import styles from './layout.module.css';

/** Отступы: шаги из tokens.css. */
export type Gap = 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 0;

const gap = (value: Gap | undefined): string | undefined =>
  value === undefined ? undefined : value === 0 ? '0' : `var(--mg-space-${value})`;

interface BoxProps {
  children?: ReactNode;
  gap?: Gap;
  className?: string;
  style?: CSSProperties;
}

/** Элементы друг под другом. */
export function Stack({ children, gap: g = 'md', className, style }: BoxProps) {
  return (
    <div className={[styles.stack, className].filter(Boolean).join(' ')} style={{ gap: gap(g), ...style }}>
      {children}
    </div>
  );
}

/** Элементы в строку с переносом. */
export function Row({ children, gap: g = 'sm', className, style, justify }: BoxProps & {
  justify?: CSSProperties['justifyContent'];
}) {
  return (
    <div
      className={[styles.row, className].filter(Boolean).join(' ')}
      style={{ gap: gap(g), justifyContent: justify, ...style }}
    >
      {children}
    </div>
  );
}

/** Сетка карточек: столбцов столько, сколько помещается при ширине не меньше minWidth. */
export function Grid({ children, gap: g = 'md', minWidth = 220 }: BoxProps & { minWidth?: number }) {
  return (
    <div className={styles.grid} style={{ gap: gap(g), ['--grid-min' as string]: `${minWidth}px` }}>
      {children}
    </div>
  );
}

export function Card({ children, className, style }: BoxProps) {
  return <div className={[styles.card, className].filter(Boolean).join(' ')} style={style}>{children}</div>;
}

/** Содержимое страницы по центру с ограниченной шириной. */
export function Page({ children }: { children: ReactNode }) {
  return <main className={styles.page}>{children}</main>;
}

export function Title({ children, level = 2 }: { children: ReactNode; level?: 1 | 2 | 3 | 4 }) {
  const Tag = `h${level}` as ElementType;
  const size = { 1: '1.75rem', 2: '1.375rem', 3: '1.125rem', 4: '1rem' }[level];
  return <Tag className={styles.title} style={{ fontSize: size }}>{children}</Tag>;
}

export function Text({ children, muted, small, as: Tag = 'p', style }: {
  children: ReactNode;
  muted?: boolean;
  small?: boolean;
  as?: ElementType;
  style?: CSSProperties;
}) {
  const className = [styles.text, muted && styles.muted, small && styles.small].filter(Boolean).join(' ');
  return <Tag className={className} style={style}>{children}</Tag>;
}
