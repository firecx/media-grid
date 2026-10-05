import { Menu as MMenu, Modal as MModal } from '@mantine/core';
import type { ReactNode } from 'react';

export function Modal({ opened, onClose, title, children, wide }: {
  opened: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  wide?: boolean;
}) {
  return (
    <MModal opened={opened} onClose={onClose} title={title} size={wide ? 'lg' : 'md'} centered>
      {children}
    </MModal>
  );
}

export interface MenuItem {
  label: string;
  icon?: ReactNode;
  onClick: () => void;
  danger?: boolean;
}

/** Выпадающее меню у кнопки trigger. */
export function Menu({ trigger, items, label }: { trigger: ReactNode; items: MenuItem[]; label?: string }) {
  return (
    <MMenu position="bottom-end" withArrow shadow="md">
      <MMenu.Target>{trigger}</MMenu.Target>
      <MMenu.Dropdown>
        {label && <MMenu.Label>{label}</MMenu.Label>}
        {items.map((item) => (
          <MMenu.Item key={item.label} leftSection={item.icon} onClick={item.onClick}
            color={item.danger ? 'red' : undefined}>
            {item.label}
          </MMenu.Item>
        ))}
      </MMenu.Dropdown>
    </MMenu>
  );
}
