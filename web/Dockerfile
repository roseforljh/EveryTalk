# 官网与网页版自托管镜像，只含前端。NEXT_PUBLIC_* 在构建时写入前端，必须作为构建参数传入；
# 生图、搜索、网页读取等密钥都在独立部署的后端配置。
FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN npm install -g bun@1
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY . .
ARG NEXT_PUBLIC_SUPABASE_URL
ARG NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_TELEMETRY_DISABLED=1
RUN bun run build

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /app ./
USER node
EXPOSE 3000
CMD ["node_modules/.bin/next", "start"]
