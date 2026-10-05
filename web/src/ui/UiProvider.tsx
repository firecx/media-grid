import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/dropzone/styles.css';
import './tokens.css';
import './global.css';

import { createTheme, localStorageColorSchemeManager, MantineProvider } from '@mantine/core';
import { ModalsProvider } from '@mantine/modals';
import { Notifications } from '@mantine/notifications';
import type { ReactNode } from 'react';

/** Тема Mantine подстраивается под собственные переменные (tokens.css), а не наоборот. */
const theme = createTheme({
  fontFamily: 'var(--mg-font)',
  primaryColor: 'indigo',
  defaultRadius: 'md',
  cursorType: 'pointer',
});

const colorSchemeManager = localStorageColorSchemeManager({ key: 'mediagrid-color-scheme' });

/** Оформление всего приложения: тема, уведомления, окна подтверждения. */
export function UiProvider({ children }: { children: ReactNode }) {
  return (
    <MantineProvider theme={theme} defaultColorScheme="auto" colorSchemeManager={colorSchemeManager}>
      <ModalsProvider labels={{ confirm: 'Да', cancel: 'Отмена' }}>
        <Notifications position="top-right" />
        {children}
      </ModalsProvider>
    </MantineProvider>
  );
}
