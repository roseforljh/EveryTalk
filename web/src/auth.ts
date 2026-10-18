import { createClient, type Session, type SupabaseClient } from "@supabase/supabase-js";
import { isObject } from "./sync-core";
import type { AuthSession } from "./types";

export const authConfig = {
  url: process.env.NEXT_PUBLIC_SUPABASE_URL?.trim().replace(/\/$/, "") ?? "",
  key: process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY?.trim() ?? "",
};
export const syncBaseUrl = process.env.NEXT_PUBLIC_SYNC_BASE_URL?.trim().replace(/\/$/, "") ?? "";

/** 只允许公开浏览器 Key；拒绝管理员密钥，不能把它们打包到网页。 */
export function configuredAuth(): boolean {
  try {
    const url = new URL(authConfig.url);
    const key = authConfig.key;
    const anonymous = key.split(".").length === 3 && JSON.parse(atob(key.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).role === "anon";
    return url.protocol === "https:" && url.pathname === "/" && !url.username && !url.password && !url.search && !url.hash &&
      (key.startsWith("sb_publishable_") || anonymous);
  } catch { return false; }
}

export class SessionExpiredError extends Error {
  constructor() { super("登录已失效，请重新登录。未上传的数据仍保留在此浏览器。"); }
}

/** 登录后的同源请求最多用新令牌重试一次；刷新后账号变化立即中止，避免串号。 */
export async function authenticatedFetch(session: AuthSession, input: RequestInfo | URL, init: RequestInit = {}, refreshSession?: () => Promise<AuthSession>): Promise<Response> {
  let current = session
  for (let attempt = 0; ; attempt++) {
    const headers = new Headers(init.headers)
    headers.set("Authorization", `Bearer ${current.access_token}`)
    const response = await fetch(input, { ...init, headers })
    if (response.status !== 401 || !refreshSession || attempt > 0) return response
    await response.body?.cancel().catch(() => undefined)
    current = await refreshSession()
    if (current.user.id !== session.user.id) throw new SessionExpiredError()
  }
}

function mapSession(session: Session): AuthSession {
  return { access_token: session.access_token, refresh_token: session.refresh_token,
    expires_at: (session.expires_at ?? 0) * 1000, user: session.user };
}

/**
 * 官方 SDK 负责 PKCE、刷新锁和跨标签页会话通知。页面只用此出口，
 * 退出时先锁住界面；网络或 5xx 失败保留会话，只有明确失效才要求重新登录。
 */
export class AuthClient {
  private sdk?: SupabaseClient;
  private initialization?: Promise<AuthSession | null>;
  private refreshing?: Promise<AuthSession>;
  private oauthProviders?: Promise<Set<string>>;

  private get client(): SupabaseClient {
    if (!configuredAuth()) throw new Error("账号服务配置缺失或无效，请联系维护者");
    this.sdk ??= createClient(authConfig.url, authConfig.key, { auth: {
      flowType: "pkce", detectSessionInUrl: false, persistSession: true, autoRefreshToken: true,
      storageKey: `everytalk-web-auth:${new URL(authConfig.url).hostname}`,
    } });
    return this.sdk;
  }

  initialize(): Promise<AuthSession | null> {
    // React 开发模式会重复挂载：同一个授权码只能交换一次。
    this.initialization ??= this.restoreCallbackOrSession();
    return this.initialization;
  }

  private async restoreCallbackOrSession(): Promise<AuthSession | null> {
    if (!configuredAuth()) return null;
    const url = new URL(window.location.href);
    if (url.pathname === "/auth/callback") {
      try {
        const pending = JSON.parse(sessionStorage.getItem("everytalk-oauth") ?? "null") as { state: string; at: number } | null;
        const state = url.searchParams.get("login_state");
        if (!pending || pending.state !== state || Date.now() - pending.at > 600_000 || Date.now() < pending.at ||
          url.hash || url.searchParams.getAll("code").length !== 1 || !url.searchParams.get("code")) throw new Error("登录回调无效或已过期，请重新登录");
        const { data, error } = await this.client.auth.exchangeCodeForSession(url.searchParams.get("code")!);
        if (error) throw new Error("第三方登录未完成，请重新登录");
        return data.session ? mapSession(data.session) : null;
      } finally {
        sessionStorage.removeItem("everytalk-oauth");
        window.history.replaceState({}, "", "/chat");
      }
    }
    return this.restore();
  }

  async restore(): Promise<AuthSession | null> {
    if (!configuredAuth()) return null;
    const { data, error } = await this.client.auth.getSession();
    if (error) throw new Error("无法恢复登录，请检查网络后重试");
    return data.session ? mapSession(data.session) : null;
  }

  subscribe(listener: (session: AuthSession | null) => void): () => void {
    if (!configuredAuth()) return () => undefined;
    const { data } = this.client.auth.onAuthStateChange((_event, session) => listener(session ? mapSession(session) : null));
    return () => data.subscription.unsubscribe();
  }

  async sendEmailCode(email: string): Promise<void> {
    const { error } = await this.client.auth.signInWithOtp({ email: email.trim().toLowerCase(), options: { shouldCreateUser: true } });
    if (error) throw new Error(error.status === 429 ? "发送过于频繁，请稍后重试" : "验证码发送失败，请检查邮箱或稍后重试");
  }

  async verifyEmailCode(email: string, token: string): Promise<AuthSession> {
    const { data, error } = await this.client.auth.verifyOtp({ email: email.trim().toLowerCase(), token: token.trim(), type: "email" });
    if (error || !data.session) throw new Error("验证码无效或已过期，请重新获取");
    return mapSession(data.session);
  }

  async startOAuth(provider: "google" | "github"): Promise<void> {
    const state = crypto.randomUUID();
    sessionStorage.setItem("everytalk-oauth", JSON.stringify({ state, at: Date.now() }));
    const { error } = await this.client.auth.signInWithOAuth({ provider, options: {
      redirectTo: `${window.location.origin}/auth/callback?login_state=${state}`,
      scopes: provider === "google" ? "openid email profile" : "read:user user:email",
    } });
    if (error) throw new Error("无法开始第三方登录，请稍后重试");
  }

  /** 从 Supabase 公共设置读取真实启用的 OAuth 提供商，避免展示无法使用的登录按钮。 */
  async availableOAuthProviders(): Promise<Set<string>> {
    this.oauthProviders ??= (async () => {
      if (!configuredAuth()) return new Set<string>();
      try {
        const response = await fetch(`${authConfig.url}/auth/v1/settings`, { headers: { apikey: authConfig.key }, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(10_000) });
        if (!response.ok) return new Set<string>();
        const body: unknown = await response.json();
        // Supabase 按 external.google / external.github 返回布尔值，不提供 providers 列表。
        const external = isObject(body) && isObject(body.external) ? body.external : {};
        return new Set(["google", "github"].filter(provider => external[provider] === true));
      } catch { return new Set<string>(); }
    })();
    return this.oauthProviders;
  }

  refresh(current: AuthSession): Promise<AuthSession> {
    this.refreshing ??= this.refreshOnce(current).finally(() => { this.refreshing = undefined; });
    return this.refreshing;
  }

  private async refreshOnce(current: AuthSession): Promise<AuthSession> {
    const { data, error } = await this.client.auth.refreshSession({ refresh_token: current.refresh_token });
    if (error || !data.session) {
      if (error?.status === 400 || error?.status === 401 || error?.status === 403) throw new SessionExpiredError();
      throw new Error("暂时无法刷新登录，请检查网络后重试");
    }
    if (data.session.user.id !== current.user.id) throw new SessionExpiredError();
    return mapSession(data.session);
  }

  async signOut(): Promise<void> {
    // local scope 只退出当前浏览器，安卓登录不受影响。
    const { error } = await this.client.auth.signOut({ scope: "local" });
    this.initialization = undefined;
    if (error) throw new Error("退出未能确认，请恢复网络后重试");
  }
}

export const authClient = new AuthClient();
