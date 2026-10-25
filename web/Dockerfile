# 官网与网页版自托管镜像。NEXT_PUBLIC_* 在构建时写入前端，必须作为构建参数传入；
# 其余服务端变量（同步地址、生图配置）在运行时通过环境变量提供。
FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN npm install -g bun@1
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY . .
ARG NEXT_PUBLIC_SUPABASE_URL
ARG NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY
ENV NEXT_TELEMETRY_DISABLED=1
RUN bun run build

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /app ./
USER node
EXPOSE 3000
CMD ["node_modules/.bin/next", "start"]
