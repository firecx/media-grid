import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: { ecmaVersion: 2023, globals: globals.browser },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Библиотека компонентов подключается только в src/ui: чтобы потом заменить её своими
      // стилями, достаточно переписать один каталог
      'no-restricted-imports': ['error', {
        patterns: [{
          group: ['@mantine/*'],
          message: 'Компоненты — только из @/ui; Mantine используется лишь внутри src/ui.',
        }],
      }],
    },
  },
  {
    files: ['src/ui/**/*.{ts,tsx}'],
    rules: { 'no-restricted-imports': 'off' },
  },
  {
    // Файл маршрутов — не компонент: при его правке страница просто перезагружается целиком
    files: ['src/router.tsx'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
);
