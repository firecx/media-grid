import { IconKey, IconLogout, IconMoon, IconSun, IconUserCircle } from '@tabler/icons-react';
import { NavLink, Outlet, useNavigate } from 'react-router';
import { session, useSession } from '@/auth/session';
import { IconButton, Menu, useColorScheme } from '@/ui';
import styles from './AppLayout.module.css';

/** Шапка с разделами и меню пользователя; под ней — текущая страница. */
export function AppLayout() {
  const { user, isAdmin } = useSession();
  const { dark, toggle } = useColorScheme();
  const navigate = useNavigate();

  const link = ({ isActive }: { isActive: boolean }) => (isActive ? `${styles.link} ${styles.active}` : styles.link);

  return (
    <>
      <header className={styles.header}>
        <div className={styles.inner}>
          <NavLink to="/" className={styles.brand}>MediaGrid</NavLink>
          <nav className={styles.nav} aria-label="Разделы">
            <NavLink to="/" end className={link}>Каталог</NavLink>
            <NavLink to="/upload" className={link}>Загрузка</NavLink>
            {isAdmin && <NavLink to="/admin" className={link}>Администрирование</NavLink>}
          </nav>
          <div className={styles.tools}>
            <IconButton label={dark ? 'Светлая тема' : 'Тёмная тема'} onClick={toggle}>
              {dark ? <IconSun size={20} /> : <IconMoon size={20} />}
            </IconButton>
            <Menu
              label={user?.email}
              trigger={<span><IconButton label={user?.displayName ?? 'Учётная запись'}><IconUserCircle size={22} /></IconButton></span>}
              items={[
                { label: 'Сменить пароль', icon: <IconKey size={16} />, onClick: () => navigate('/account/password') },
                {
                  label: 'Выйти',
                  icon: <IconLogout size={16} />,
                  danger: true,
                  onClick: () => void session.logout().then(() => navigate('/login')),
                },
              ]}
            />
          </div>
        </div>
      </header>
      <Outlet />
    </>
  );
}
