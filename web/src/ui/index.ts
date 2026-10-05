// Единственная точка доступа страниц к компонентам. Mantine подключается только внутри этого каталога:
// чтобы перейти на свои стили, достаточно заново реализовать то, что экспортируется отсюда.
export { Button, IconButton } from './actions';
export type { ButtonKind, ButtonProps } from './actions';
export { Pagination, Table } from './data';
export type { Column } from './data';
export { confirm, notify } from './dialogs';
export { Alert, Badge, Loader, Progress } from './feedback';
export type { Tone } from './tones';
export { FileDrop } from './FileDrop';
export { CheckboxField, PasswordField, SelectField, SwitchField, TagsField, TextAreaField, TextField } from './forms';
export type { Option } from './forms';
export { Card, Grid, Page, Row, Stack, Text, Title } from './layout';
export type { Gap } from './layout';
export { Menu, Modal } from './overlay';
export type { MenuItem } from './overlay';
export { useColorScheme } from './theme';
export { UiProvider } from './UiProvider';
