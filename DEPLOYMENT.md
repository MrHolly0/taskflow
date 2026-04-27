# Deployment Guide

TaskFlow deployment guide for local development and VPS production.

## Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- Java 21 (for local development)
- Node 20+ (for local development)
- A domain name (for production TLS)

## Local Development

### 1. Environment Setup

```bash
cp .env.example .env
# Edit .env with your values, especially:
# - TELEGRAM_BOT_TOKEN
# - GROQ_API_KEY
```

### 2. Start Infrastructure Only

```bash
docker compose -f infra/docker-compose.yml up -d postgres redis
```

### 3. Run Services Locally

```bash
# Terminal 1: Core service
./gradlew :core-service:app:bootRun

# Terminal 2: NLP worker
./gradlew :nlp-worker:bootRun

# Terminal 3: Notification worker
./gradlew :notification-worker:bootRun

# Terminal 4: Frontend
cd miniapp && pnpm dev
```

Access:
- API: http://localhost:8080
- Mini App: http://localhost:5173
- pgAdmin: http://localhost:5050 (user/pass: admin@taskflow.local/admin)

## Docker Compose (Local Development)

### Full Stack with Docker

```bash
docker compose -f infra/docker-compose.yml up -d
```

Services:
- Core service: http://localhost:8080
- NLP worker: http://localhost:8081 (behind nginx)
- Notification worker: http://localhost:8082 (behind nginx)
- Mini App: http://localhost:3000 (via nginx)
- Nginx reverse proxy: http://localhost

Access via nginx:
- Mini App: http://localhost/
- API: http://localhost/api/v1
- Health: http://localhost/health

### Verify Services

```bash
# Check all services are running
docker compose -f infra/docker-compose.yml ps

# View logs
docker compose -f infra/docker-compose.yml logs -f core-service

# Health check
curl http://localhost/health
```

### Cleanup

```bash
docker compose -f infra/docker-compose.yml down
# Keep volumes:
docker compose -f infra/docker-compose.yml down --volumes  # Remove volumes
```

## VPS Production Deployment

### 1. Server Preparation

```bash
# SSH into your VPS
ssh user@your-server.com

# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# Install Docker Compose
sudo curl -L "https://github.com/docker/compose/releases/download/v2.20.2/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# Install Certbot for TLS
sudo apt install -y certbot python3-certbot-nginx
```

### 2. Domain & SSL Setup

```bash
# Point your domain to the VPS IP in DNS

# Request Let's Encrypt certificate
sudo certbot certonly --standalone -d your-domain.com -d www.your-domain.com

# Certbot will store certificates in /etc/letsencrypt/live/your-domain.com/
```

### 3. Clone Repository

```bash
cd /opt
sudo git clone <your-repo-url> taskflow
cd taskflow

# Copy and configure production .env
cp .env.example .env
nano .env  # Edit with production values
```

### 4. Configure Production Nginx

Update `infra/nginx/nginx.conf` to use your domain and certificates:

```nginx
server {
    listen 443 ssl http2;
    server_name your-domain.com www.your-domain.com;

    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    
    # ... rest of config
}

# HTTP to HTTPS redirect
server {
    listen 80;
    server_name your-domain.com www.your-domain.com;
    return 301 https://$server_name$request_uri;
}
```

### 5. Update Environment

Set production values in `.env`:

```bash
# Update APP_DOMAIN for Mini App API URL
APP_DOMAIN=your-domain.com

# Update Telegram webhook URL
TELEGRAM_WEBHOOK_URL=https://your-domain.com/telegram/webhook

# Use strong JWT_SECRET (generate with: openssl rand -hex 32)
JWT_SECRET=<generated-secret>
```

### 6. Deploy

```bash
cd /opt/taskflow

# Build all images
docker compose -f infra/docker-compose.prod.yml build

# Start services
docker compose -f infra/docker-compose.prod.yml up -d

# Check status
docker compose -f infra/docker-compose.prod.yml ps

# View logs
docker compose -f infra/docker-compose.prod.yml logs -f
```

### 7. Verify Deployment

```bash
# Health check
curl https://your-domain.com/health

# Test API
curl https://your-domain.com/api/v1/tasks/focus

# Check SSL certificate
curl -vI https://your-domain.com
```

### 8. Register Telegram Webhook

```bash
curl -X POST https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook \
  -H "Content-Type: application/json" \
  -d "{\"url\": \"https://your-domain.com/telegram/webhook\", \"secret_token\": \"${TELEGRAM_WEBHOOK_SECRET}\"}"
```

## Monitoring & Maintenance

### View Logs

```bash
# All services
docker compose -f infra/docker-compose.prod.yml logs -f

# Specific service
docker compose -f infra/docker-compose.prod.yml logs -f core-service

# Follow in real-time
docker compose -f infra/docker-compose.prod.yml logs -f --tail=100
```

### SSL Certificate Renewal

Certbot should auto-renew before expiration (30 days before).

```bash
# Manual renewal
sudo certbot renew --dry-run  # Test renewal
sudo certbot renew            # Actual renewal
```

### Backup Database

```bash
# Backup PostgreSQL
docker compose -f infra/docker-compose.prod.yml exec postgres \
  pg_dump -U taskflow taskflow > backup_$(date +%Y%m%d).sql

# Restore from backup
docker compose -f infra/docker-compose.prod.yml exec -T postgres \
  psql -U taskflow taskflow < backup_20240426.sql
```

### Update Services

```bash
# Pull latest code
git pull origin main

# Rebuild and restart
docker compose -f infra/docker-compose.prod.yml up -d --build

# Verify
docker compose -f infra/docker-compose.prod.yml ps
```

## Troubleshooting

### Services won't start

```bash
# Check logs
docker compose -f infra/docker-compose.prod.yml logs

# Check port conflicts
netstat -tlnp | grep :80
netstat -tlnp | grep :443

# Restart all services
docker compose -f infra/docker-compose.prod.yml restart
```

### API connection errors

```bash
# Check core-service is healthy
docker compose -f infra/docker-compose.prod.yml exec core-service \
  curl http://localhost:8080/actuator/health

# Check nginx reverse proxy is working
docker compose -f infra/docker-compose.prod.yml exec nginx \
  curl http://core-service:8080/actuator/health
```

### Database connection errors

```bash
# Check PostgreSQL is running and healthy
docker compose -f infra/docker-compose.prod.yml ps postgres

# Connect to database
docker compose -f infra/docker-compose.prod.yml exec postgres \
  psql -U taskflow -d taskflow -c "SELECT 1"
```

### TLS certificate issues

```bash
# Check certificate expiration
echo | openssl s_client -servername your-domain.com -connect your-domain.com:443 2>/dev/null | openssl x509 -noout -dates

# Force renewal
sudo certbot renew --force-renewal
```

## Resource Management

`docker-compose.prod.yml` не включает жёстких лимитов ресурсов — Docker Swarm `deploy.resources` не поддерживается в standalone compose. При необходимости ограничьте через cgroups на уровне хоста или используйте Kubernetes.

## Environment Variables Reference

| Variable | Required | Default | Notes |
|----------|----------|---------|-------|
| DB_HOST | Yes (prod) | localhost | PostgreSQL host |
| DB_PORT | No | 5432 | PostgreSQL port |
| DB_NAME | No | taskflow | Database name |
| DB_USER | No | taskflow | Database user |
| DB_PASSWORD | No | taskflow | Database password |
| REDIS_HOST | No | localhost | Redis host |
| REDIS_PORT | No | 6379 | Redis port |
| REDIS_PASSWORD | No | taskflow | Redis password |
| TELEGRAM_BOT_TOKEN | Yes | | From BotFather |
| TELEGRAM_BOT_USERNAME | Yes | | Bot username |
| TELEGRAM_WEBHOOK_URL | Yes (prod) | | Full webhook URL |
| TELEGRAM_WEBHOOK_SECRET | Yes (prod) | | Secret token |
| GROQ_API_KEY | Yes | | Groq API key |
| JWT_SECRET | No | changeme-in-production | Use strong secret in prod |
| JWT_ACCESS_TTL_MINUTES | No | 60 | JWT access token lifetime |
| JWT_REFRESH_TTL_DAYS | No | 30 | JWT refresh token lifetime |
| SERVER_PORT | No | 8080 | Core service port |
| NLP_PORT | No | 8081 | NLP worker port |
| NOTIFICATION_PORT | No | 8082 | Notification worker port |
| MINIAPP_PORT | No | 3000 | Mini App port |
| VITE_API_URL | No | /api/v1 | Frontend API URL (build-time ARG, по умолчанию — относительный путь через nginx) |
| APP_DOMAIN | No | localhost | Domain for production |

## Support

For issues, check logs and verify all environment variables are correctly set in `.env`.
