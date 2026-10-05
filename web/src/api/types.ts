// Ответы служб (см. README служб).

export type Role = 'ADMIN' | 'USER';

export interface User {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  enabled: boolean;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export type MediaKind = 'IMAGE' | 'VIDEO' | 'AUDIO';
export type MediaStatus = 'PENDING_UPLOAD' | 'UPLOADED' | 'READY' | 'FAILED';
export type Visibility = 'PRIVATE' | 'PUBLIC';

export interface Category {
  id: string;
  name: string;
  description: string | null;
}

export interface Media {
  id: string;
  ownerId: string;
  title: string;
  originalFilename: string;
  contentType: string;
  mediaKind: MediaKind;
  sizeBytes: number;
  status: MediaStatus;
  visibility: Visibility;
  category: Category | null;
  tags: string[];
  hasPreview: boolean;
  processingError: string | null;
  createdAt: string;
  updatedAt: string;
  uploadedAt: string | null;
}

export type Variant = 'original' | 'playback' | 'preview' | 'thumbnail';

export interface FileLink {
  url: string;
  expiresAt: string;
  variant: Variant;
}

export interface UploadState {
  mediaId: string;
  receivedBytes: number;
  expectedBytes: number;
  complete: boolean;
}

export type JobStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED';
export type JobStage = 'DOWNLOADING' | 'ANALYZING' | 'PREVIEW' | 'TRANSCODING' | 'SAVING';

export interface ProcessingJob {
  mediaId: string;
  status: JobStatus;
  stage: JobStage | null;
  progress: number;
  attempts: number;
  nextAttemptAt: string | null;
  error: string | null;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  transcoded: boolean;
  hasPreview: boolean;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}
