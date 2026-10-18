export type JsonObject = Record<string, unknown>;

export type SyncEntity = "sessions" | "messages" | "memories" | "user_settings" | "attachments";

export interface SyncRecord {
  entity: SyncEntity;
  id: string;
  payload: JsonObject;
  deleted: boolean;
  server_seq?: number;
  created_at?: string;
  updated_at?: string;
}

export interface PendingChange {
  key: string;
  entity: SyncEntity;
  id: string;
  payload: JsonObject;
  deleted: boolean;
  failed?: string;
  /** 每次本地保存使用新版本，上传确认不得清掉网络请求期间的新编辑。 */
  revision: string;
}

export interface PullResponse {
  changes: SyncRecord[];
  next_cursor: number;
  has_more: boolean;
  epoch: number;
  secrets_epoch: number;
}

export interface PushResult {
  index: number;
  entity?: SyncEntity;
  id?: string;
  status: string;
  server_seq?: number;
  canonical_id?: string;
  error?: { code: string; message: string };
}

export interface PushResponse { results: PushResult[]; }

export interface StoredRecord extends SyncRecord {
  key: string;
  pending?: boolean;
}

export interface SyncState {
  cursor: number;
  epoch?: number;
  secretsEpoch?: number;
  pausedEpoch?: number;
  lastSyncAt?: number;
  retryAt?: number;
}

export interface AuthUser {
  id: string;
  email?: string;
  user_metadata?: JsonObject;
}

export interface AuthSession {
  access_token: string;
  refresh_token: string;
  expires_at: number;
  user: AuthUser;
}

export const syncKey = (entity: SyncEntity, id: string): string => `${entity}:${id}`;
