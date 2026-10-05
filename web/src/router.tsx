import { lazy, type ReactNode, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router';
import { RequireAdmin, RequireAuth } from '@/auth/guards';
import { LoginPage } from '@/features/auth/LoginPage';
import { CatalogPage } from '@/features/catalog/CatalogPage';
import { AppLayout } from '@/features/layout/AppLayout';
import { NotFoundPage } from '@/features/layout/NotFoundPage';
import { Loader, Page } from '@/ui';

// Каталог и вход — сразу; остальные разделы подгружаются при первом переходе
const MediaPage = lazy(() => import('@/features/media/MediaPage').then((m) => ({ default: m.MediaPage })));
const UploadPage = lazy(() => import('@/features/upload/UploadPage').then((m) => ({ default: m.UploadPage })));
const PasswordPage = lazy(() => import('@/features/account/PasswordPage').then((m) => ({ default: m.PasswordPage })));
const UsersPage = lazy(() => import('@/features/admin/UsersPage').then((m) => ({ default: m.UsersPage })));
const CategoriesPage = lazy(() => import('@/features/admin/CategoriesPage').then((m) => ({ default: m.CategoriesPage })));
const ProcessingQueuePage = lazy(() =>
  import('@/features/admin/ProcessingQueuePage').then((m) => ({ default: m.ProcessingQueuePage })));

function Deferred({ children }: { children: ReactNode }) {
  return <Suspense fallback={<Page><Loader /></Page>}>{children}</Suspense>;
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth><AppLayout /></RequireAuth>,
    children: [
      { index: true, element: <CatalogPage /> },
      { path: 'media/:id', element: <Deferred><MediaPage /></Deferred> },
      { path: 'upload', element: <Deferred><UploadPage /></Deferred> },
      { path: 'account/password', element: <Deferred><PasswordPage /></Deferred> },
      { path: 'admin', element: <Navigate to="/admin/users" replace /> },
      { path: 'admin/users', element: <RequireAdmin><Deferred><UsersPage /></Deferred></RequireAdmin> },
      { path: 'admin/categories', element: <RequireAdmin><Deferred><CategoriesPage /></Deferred></RequireAdmin> },
      { path: 'admin/processing', element: <RequireAdmin><Deferred><ProcessingQueuePage /></Deferred></RequireAdmin> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
