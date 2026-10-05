import { Alert as MAlert, Badge as MBadge, Loader as MLoader, Progress as MProgress } from '@mantine/core';
import type { ReactNode } from 'react';
import styles from './badge.module.css';
import { type Tone, toneColor } from './tones';

/** Значок-метка. В тёмной теме подложка и рамка — из badge.module.css, по цвету оттенка --mg-tone-*. */
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return (
    <MBadge color={toneColor[tone]} variant="light" radius="sm" className={styles.badge}
      style={{ textTransform: 'none', ['--tone' as string]: `var(--mg-tone-${tone})` }}>
      {children}
    </MBadge>
  );
}

export function Alert({ title, children, tone = 'info' }: { title?: string; children?: ReactNode; tone?: Tone }) {
  return <MAlert title={title} color={toneColor[tone]} variant="light">{children}</MAlert>;
}

export function Loader({ label = 'Загрузка…' }: { label?: string }) {
  return <MLoader size="sm" aria-label={label} />;
}

/** Ход длительной операции, 0–100. */
export function Progress({ value, tone = 'info', animated, label }: {
  value: number;
  tone?: Tone;
  animated?: boolean;
  label: string;
}) {
  return (
    <MProgress value={value} color={toneColor[tone]} animated={animated} striped={animated} size="sm"
      aria-label={label} transitionDuration={200} />
  );
}
