import { api } from './http';
import type {
  Category, FileLink, JobStatus, Media, MediaKind, MediaStatus, Page, ProcessingJob, Role, TokenResponse, User,
  Variant, Visibility,
} from './types';

// --- служба авторизации ---

export const authApi = {
  login: (email: string, password: string) =>
    api<TokenResponse>('/api/auth/login', { method: 'POST', json: { email, password }, anonymous: true }),
  refresh: () => api<TokenResponse>('/api/auth/refresh', { method: 'POST', anonymous: true }),
  logout: () => api<void>('/api/auth/logout', { method: 'POST', anonymous: true }),
  changePassword: (currentPassword: string, newPassword: string) =>
    api<void>('/api/auth/me/password', { method: 'PUT', json: { currentPassword, newPassword } }),

  users: (page: number, size: number) => api<Page<User>>('/api/auth/admin/users', { query: { page, size } }),
  createUser: (user: { email: string; password: string; displayName: string; role: Role }) =>
    api<User>('/api/auth/admin/users', { method: 'POST', json: user }),
  updateUser: (id: string, changes: { displayName?: string; role?: Role; enabled?: boolean }) =>
    api<User>(`/api/auth/admin/users/${id}`, { method: 'PATCH', json: changes }),
  resetPassword: (id: string, newPassword: string) =>
    api<void>(`/api/auth/admin/users/${id}/password`, { method: 'PUT', json: { newPassword } }),
};

// --- служба медиаданных ---

export interface MediaFilter {
  q?: string;
  type?: MediaKind | null;
  status?: MediaStatus | null;
  categoryId?: string | null;
  tag?: string[];
  mine?: boolean;
  sort?: 'createdAt' | 'title' | 'size';
  direction?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface NewMedia {
  title: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  categoryId?: string | null;
  tags?: string[];
  visibility?: Visibility;
}

export interface MediaChanges {
  title?: string;
  categoryId?: string;
  removeCategory?: boolean;
  tags?: string[];
  visibility?: Visibility;
}

export const mediaApi = {
  search: (filter: MediaFilter) => api<Page<Media>>('/api/media', { query: { ...filter } }),
  get: (id: string) => api<Media>(`/api/media/${id}`),
  create: (media: NewMedia) => api<Media>('/api/media', { method: 'POST', json: media }),
  update: (id: string, changes: MediaChanges) => api<Media>(`/api/media/${id}`, { method: 'PATCH', json: changes }),
  remove: (id: string) => api<void>(`/api/media/${id}`, { method: 'DELETE' }),
  categories: () => api<Category[]>('/api/media/categories'),
  createCategory: (name: string, description: string) =>
    api<Category>('/api/media/admin/categories', { method: 'POST', json: { name, description } }),
  updateCategory: (id: string, name: string, description: string) =>
    api<Category>(`/api/media/admin/categories/${id}`, { method: 'PUT', json: { name, description } }),
  removeCategory: (id: string) => api<void>(`/api/media/admin/categories/${id}`, { method: 'DELETE' }),
};

// --- служба хранения ---

export const filesApi = {
  link: (id: string, variant: Variant, download = false) =>
    api<FileLink>(`/api/files/${id}/links`, { method: 'POST', json: { variant, download } }),
};

// --- служба обработки ---

export const processingApi = {
  job: (id: string) => api<ProcessingJob>(`/api/processing/${id}`),
  jobs: (status: JobStatus | null, page: number, size: number) =>
    api<Page<ProcessingJob>>('/api/processing/admin/jobs', { query: { status, page, size } }),
  retry: (id: string) => api<ProcessingJob>(`/api/processing/admin/jobs/${id}/retry`, { method: 'POST' }),
};
