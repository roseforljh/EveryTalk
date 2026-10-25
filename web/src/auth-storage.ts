/** 浏览器会话的本地存储键。官网只读取它判断登录状态，不必加载账号 SDK。 */
export function authStorageKey(url = process.env.NEXT_PUBLIC_SUPABASE_URL?.trim() ?? ""): string {
  try { return `everytalk-web-auth:${new URL(url).hostname}`; } catch { return ""; }
}
