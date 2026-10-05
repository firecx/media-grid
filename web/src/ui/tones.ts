/**
 * Смысловой оттенок. accent, accent2, accent3 — цвета выделения, меняются вместе с темой
 * (светлая: синий, тёмно-синий, фиолетовый; тёмная: бирюзовый, аквамарин, голубо-зелёный).
 * info совпадает с основным цветом выделения.
 */
export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'accent' | 'accent2' | 'accent3';

/** Цвета темы Mantine для оттенков (accent* — меняющиеся цвета из UiProvider). */
export const toneColor: Record<Tone, string> = {
  neutral: 'gray',
  info: 'accent',
  success: 'green',
  warning: 'yellow',
  danger: 'red',
  accent: 'accent',
  accent2: 'accent2',
  accent3: 'accent3',
};
