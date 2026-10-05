import { ActionIcon, Button as MButton, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';

export type ButtonKind = 'primary' | 'secondary' | 'danger' | 'subtle';

const kinds: Record<ButtonKind, { variant: string; color?: string }> = {
  primary: { variant: 'filled' },
  secondary: { variant: 'default' },
  danger: { variant: 'filled', color: 'red' },
  subtle: { variant: 'subtle' },
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
  const { variant, color } = kinds[kind];
  const common = { variant, color, loading, disabled, leftSection: icon, fullWidth, size };
  if (href) {
    return <MButton component="a" href={href} {...common}>{children}</MButton>;
  }
  return <MButton type={type} onClick={onClick} {...common}>{children}</MButton>;
}

/** Кнопка-значок; подпись обязательна — она же подсказка и текст для программ чтения экрана. */
export function IconButton({ label, children, onClick, kind = 'subtle', disabled }: {
  label: string;
  children: ReactNode;
  onClick?: () => void;
  kind?: ButtonKind;
  disabled?: boolean;
}) {
  const { variant, color } = kinds[kind];
  return (
    <Tooltip label={label} withArrow>
      <ActionIcon variant={variant} color={color} onClick={onClick} disabled={disabled} aria-label={label} size="lg">
        {children}
      </ActionIcon>
    </Tooltip>
  );
}
