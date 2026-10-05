import { modals } from '@mantine/modals';
import { notifications } from '@mantine/notifications';
import type { ReactNode } from 'react';
import { type Tone, toneColor } from './tones';

/** Короткое всплывающее сообщение. */
export function notify(message: string, tone: Tone = 'success', title?: string) {
  notifications.show({ message, title, color: toneColor[tone], autoClose: tone === 'danger' ? 8000 : 4000 });
}

/** Подтверждение опасного действия; true — пользователь согласился. */
export function confirm({ title, message, confirmLabel = 'Да', danger = true }: {
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
}): Promise<boolean> {
  return new Promise((resolve) => {
    let answered = false;
    const answer = (value: boolean) => {
      if (!answered) {
        answered = true;
        resolve(value);
      }
    };
    modals.openConfirmModal({
      title,
      children: message,
      labels: { confirm: confirmLabel, cancel: 'Отмена' },
      confirmProps: danger ? { color: 'red' } : undefined,
      onConfirm: () => answer(true),
      onCancel: () => answer(false),
      onClose: () => answer(false),
    });
  });
}
