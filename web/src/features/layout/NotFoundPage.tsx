import { Link } from 'react-router';
import { Page, Stack, Text, Title } from '@/ui';

export function NotFoundPage() {
  return (
    <Page>
      <Stack>
        <Title>Страница не найдена</Title>
        <Text muted>Такого адреса нет. <Link to="/">Вернуться в каталог</Link></Text>
      </Stack>
    </Page>
  );
}
