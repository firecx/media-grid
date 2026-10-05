import { Dropzone } from '@mantine/dropzone';
import type { ReactNode } from 'react';

/** Поле для перетаскивания файлов; по нажатию открывает выбор файлов. */
export function FileDrop({ onFiles, accept, children, disabled }: {
  onFiles: (files: File[]) => void;
  accept?: string[];
  children: ReactNode;
  disabled?: boolean;
}) {
  return (
    <Dropzone onDrop={onFiles} accept={accept} disabled={disabled} multiple maxSize={Infinity}
      style={{ borderRadius: 'var(--mg-radius)' }}>
      {children}
    </Dropzone>
  );
}
