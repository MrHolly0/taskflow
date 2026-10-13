# Build stage
FROM node:20-alpine AS builder
WORKDIR /app

# Copy package files
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./

# Install dependencies
RUN npm install -g pnpm@10.33.2 && pnpm install --frozen-lockfile

# Copy source code
COPY . .

ARG VITE_API_URL
ENV VITE_API_URL=${VITE_API_URL}
ARG VITE_TELEGRAM_BOT_ID
ENV VITE_TELEGRAM_BOT_ID=${VITE_TELEGRAM_BOT_ID}
ARG VITE_VAPID_PUBLIC_KEY
ENV VITE_VAPID_PUBLIC_KEY=${VITE_VAPID_PUBLIC_KEY}

# Build the application
RUN pnpm build

# Runtime stage - serve with node
FROM node:20-alpine
WORKDIR /app

# Install serve to run the dist folder
RUN npm install -g serve

# Copy built files from builder
COPY --from=builder /app/dist ./dist

# Expose port
EXPOSE 3000

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:3000/ || exit 1

# -s/--single подменяет ЛЮБОЙ ненайденный путь на index.html с 200, включая
# /api/* — честный 404 задаётся явными rewrites в dist/serve.json (копия
# miniapp/public/serve.json) вместо этого флага.
CMD ["serve", "dist", "-l", "3000"]
