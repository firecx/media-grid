import { Pagination as MPagination, Table as MTable } from '@mantine/core';
import type { ReactNode } from 'react';

export interface Column<T> {
  key: string;
  title: string;
  render: (row: T) => ReactNode;
  width?: number | string;
}

/** Таблица; на узком экране прокручивается по горизонтали. */
export function Table<T>({ columns, rows, rowKey, empty = 'Ничего нет' }: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  empty?: string;
}) {
  return (
    <MTable.ScrollContainer minWidth={600}>
      <MTable striped highlightOnHover verticalSpacing="sm">
        <MTable.Thead>
          <MTable.Tr>
            {columns.map((c) => <MTable.Th key={c.key} style={{ width: c.width }}>{c.title}</MTable.Th>)}
          </MTable.Tr>
        </MTable.Thead>
        <MTable.Tbody>
          {rows.length === 0 && (
            <MTable.Tr><MTable.Td colSpan={columns.length}>{empty}</MTable.Td></MTable.Tr>
          )}
          {rows.map((row) => (
            <MTable.Tr key={rowKey(row)}>
              {columns.map((c) => <MTable.Td key={c.key}>{c.render(row)}</MTable.Td>)}
            </MTable.Tr>
          ))}
        </MTable.Tbody>
      </MTable>
    </MTable.ScrollContainer>
  );
}

/** Страницы с единицы для человека; total — число страниц. */
export function Pagination({ page, total, onChange }: { page: number; total: number; onChange: (page: number) => void }) {
  if (total <= 1) {
    return null;
  }
  return <MPagination value={page} total={total} onChange={onChange} withEdges size="sm" />;
}
