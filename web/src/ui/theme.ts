import { useComputedColorScheme, useMantineColorScheme } from '@mantine/core';

/** Светлая или тёмная тема и её переключение (выбор запоминается в браузере). */
export function useColorScheme(): { dark: boolean; toggle: () => void } {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light');
  return {
    dark: computed === 'dark',
    toggle: () => setColorScheme(computed === 'dark' ? 'light' : 'dark'),
  };
}
