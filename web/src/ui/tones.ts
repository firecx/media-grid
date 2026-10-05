/** Смысловой оттенок: нейтральный, сведения, успех, предупреждение, опасность. */
export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

/** Цвета Mantine для оттенков. */
export const toneColor: Record<Tone, string> = {
  neutral: 'gray',
  info: 'indigo',
  success: 'teal',
  warning: 'yellow',
  danger: 'red',
};
