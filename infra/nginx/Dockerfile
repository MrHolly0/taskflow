FROM node:22-alpine AS frontend
WORKDIR /app
RUN npm install -g pnpm@10.33.2
COPY miniapp/package.json miniapp/pnpm-lock.yaml ./
RUN --mount=type=cache,id=munin-pnpm,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY miniapp/ ./
ARG VITE_API_URL=/api/v1
ARG VITE_TELEGRAM_BOT_ID
ARG VITE_VAPID_PUBLIC_KEY
ENV VITE_API_URL=${VITE_API_URL} VITE_TELEGRAM_BOT_ID=${VITE_TELEGRAM_BOT_ID} VITE_VAPID_PUBLIC_KEY=${VITE_VAPID_PUBLIC_KEY}
RUN pnpm build

FROM nginx:1.30.6-alpine
COPY infra/nginx/nginx.conf /etc/nginx/nginx.conf
COPY infra/nginx/trusted-proxies.conf /etc/nginx/trusted-proxies.conf
COPY --from=frontend /app/dist /usr/share/nginx/html
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -q --spider http://127.0.0.1/healthz || exit 1
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
