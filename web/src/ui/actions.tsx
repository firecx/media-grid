import { ActionIcon, Button as MButton, Tooltip } from '@mantine/core';
import type { CSSProperties, ReactNode } from 'react';

/**
 * primary — главное действие (цвет выделения), secondary — обычное, danger — опасное,
 * subtle — без рамки в цвете выделения, plain — без рамки в цвете текста (нейтральные значки).
 */
export type ButtonKind = 'primary' | 'secondary' | 'danger' | 'subtle' | 'plain';

const kinds: Record<ButtonKind, { variant: string; color?: string; style?: CSSProperties }> = {
  primary: { variant: 'filled' },
  secondary: { variant: 'default' },
  danger: { variant: 'filled', color: 'red' },
  subtle: { variant: 'subtle' },
  plain: {
    variant: 'subtle',
    color: 'gray',
    // Цвет значка и подложка при наведении — из переменных темы: на тёмном фоне значок светлый
    style: { color: 'var(--mg-color-icon)', ['--ai-hover' as string]: 'var(--mg-color-hover)',
      ['--button-hover' as string]: 'var(--mg-color-hover)' },
  },
};

export interface ButtonProps {
  children: ReactNode;
  kind?: ButtonKind;
  type?: 'button' | 'submit';
  onClick?: () => void;
  loading?: boolean;
  disabled?: boolean;
  icon?: ReactNode;
  fullWidth?: boolean;
  size?: 'sm' | 'md';
  /** Кнопка-ссылка: например, на скачивание файла. */
  href?: string;
}

export function Button({ children, kind = 'secondary', type = 'button', onClick, loading, disabled, icon,
  fullWidth, size = 'sm', href }: ButtonProps) {
  const { variant, color, style } = kinds[kind];
  const common = { variant, color, style, loading, disabled, leftSection: icon, fullWidth, size };
  if (href) {
    return <MButton component="a" href={href} {...common}>{children}</MButton>;
  }
  return <MButton type={type} onClick={onClick} {...common}>{children}</MButton>;
}

/** Кнопка-значок; подпись обязательна — она же подсказка и текст для программ чтения экрана. */
export function IconButton({ label, children, onClick, kind = 'plain', disabled }: {
  label: string;
  children: ReactNode;
  onClick?: () => void;
  kind?: ButtonKind;
  disabled?: boolean;
}) {
  const { variant, color, style } = kinds[kind];
  return (
    <Tooltip label={label} withArrow>
      <ActionIcon variant={variant} color={color} style={style} onClick={onClick} disabled={disabled}
        aria-label={label} size="lg">
        {children}
      </ActionIcon>
    </Tooltip>
  );
}
