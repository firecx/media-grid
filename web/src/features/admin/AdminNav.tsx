import { NavLink } from 'react-router';
import { Row, Title } from '@/ui';
import styles from './AdminNav.module.css';

/** Подразделы администрирования (ТЗ, п. 4.1.8: административные функции отделены от пользовательских). */
export function AdminNav({ title }: { title: string }) {
  const link = ({ isActive }: { isActive: boolean }) => (isActive ? `${styles.tab} ${styles.active}` : styles.tab);
  return (
    <>
      <Title level={1}>{title}</Title>
      <Row gap="xs" className={styles.tabs}>
        <NavLink to="/admin/users" className={link}>Пользователи</NavLink>
        <NavLink to="/admin/categories" className={link}>Категории</NavLink>
        <NavLink to="/admin/processing" className={link}>Обработка</NavLink>
      </Row>
    </>
  );
}
