import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/dropzone/styles.css';
import './tokens.css';
import './global.css';

import {
  createTheme, type CSSVariablesResolver, localStorageColorSchemeManager, MantineProvider, type MantineColorsTuple,
  virtualColor,
} from '@mantine/core';
import { ModalsProvider } from '@mantine/modals';
import { Notifications } from '@mantine/notifications';
import type { ReactNode } from 'react';
import { COLOR_SCHEME_KEY } from './scheme';

// Оттенки для тёмной темы, от светлого (0) к тёмному (9)
const turquoise: MantineColorsTuple = [
  '#ddfcf9', '#bdf6f1', '#8eeee6', '#5ee5da', '#3ddbcd', '#25c7b9', '#17a99d', '#11887f', '#0d6a63', '#084d48',
];
const aquamarine: MantineColorsTuple = [
  '#e2fff4', '#c4ffea', '#a0fbdc', '#7cf5c9', '#5cecb7', '#3fd9a0', '#2cb985', '#21946a', '#187251', '#0f5039',
];
const blueGreen: MantineColorsTuple = [
  '#dff8fb', '#b9eef4', '#8ce2ec', '#5fd4e3', '#3cc8dc', '#22b0c4', '#1690a2', '#107281', '#0b5661', '#073c44',
];
// Тёмные поверхности Mantine (поля, окна, меню) — в тон переменным tokens.css
const dark: MantineColorsTuple = [
  '#eef2f6', '#d3dae2', '#b3bdc9', '#8a95a3', '#3a4450', '#2e3742', '#222a33', '#1b2129', '#151a20', '#0d1014',
];

/**
 * Цвета выделения меняются вместе с темой: в светлой — синий, тёмно-синий, фиолетовый;
 * в тёмной — бирюзовый, аквамарин, голубо-зелёный. Компоненты обращаются к ним по одному имени.
 */
const theme = createTheme({
  fontFamily: 'var(--mg-font)',
  colors: {
    dark,
    turquoise,
    aquamarine,
    blueGreen,
    accent: virtualColor({ name: 'accent', light: 'blue', dark: 'turquoise' }),
    accent2: virtualColor({ name: 'accent2', light: 'indigo', dark: 'aquamarine' }),
    accent3: virtualColor({ name: 'accent3', light: 'violet', dark: 'blueGreen' }),
  },
  primaryColor: 'accent',
  // В тёмной теме — светлый оттенок: яркое выделение на тёмном фоне
  primaryShade: { light: 7, dark: 4 },
  // Текст на залитых кнопках и значках — чёрный или белый, смотря что виднее на этом цвете
  autoContrast: true,
  luminanceThreshold: 0.45,
  defaultRadius: 'md',
  cursorType: 'pointer',
});

/** Фон, текст, поля и рамки Mantine берёт из собственных переменных — общих со страницами. */
const variables: CSSVariablesResolver = () => {
  const common = {
    '--mantine-color-body': 'var(--mg-color-surface)',
    '--mantine-color-text': 'var(--mg-color-text)',
    '--mantine-color-bright': 'var(--mg-color-text)',
    '--mantine-color-dimmed': 'var(--mg-color-muted)',
    '--mantine-color-placeholder': 'var(--mg-color-placeholder)',
    '--mantine-color-anchor': 'var(--mg-color-accent)',
    '--mantine-color-default': 'var(--mg-color-surface)',
    '--mantine-color-default-hover': 'var(--mg-color-hover)',
    '--mantine-color-default-color': 'var(--mg-color-text)',
    '--mantine-color-default-border': 'var(--mg-color-border)',
  };
  return { variables: {}, light: common, dark: common };
};

const colorSchemeManager = localStorageColorSchemeManager({ key: COLOR_SCHEME_KEY });

/** Оформление всего приложения: тема, уведомления, окна подтверждения. */
export function UiProvider({ children }: { children: ReactNode }) {
  return (
    <MantineProvider theme={theme} defaultColorScheme="auto" colorSchemeManager={colorSchemeManager}
      cssVariablesResolver={variables}>
      <ModalsProvider labels={{ confirm: 'Да', cancel: 'Отмена' }}>
        <Notifications position="top-right" />
        {children}
      </ModalsProvider>
    </MantineProvider>
  );
}
