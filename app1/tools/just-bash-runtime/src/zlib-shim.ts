/** 浏览器 Bundle 中 zlib 只用于可选压缩数据路径；基础 Shell 不依赖它。 */
export function gunzipSync(input: Uint8Array): Uint8Array {
  throw new Error("gzip input is not supported in the Android just-bash runtime");
}

export function gzipSync(input: Uint8Array): Uint8Array {
  throw new Error("gzip output is not supported in the Android just-bash runtime");
}

export const constants = {};
