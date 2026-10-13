#!/bin/bash
set -e
cd "$(dirname "$0")"
URL=$(curl -fsS http://localhost:4040/api/tunnels | python3 -c "import json,sys; print(next((t['public_url'] for t in json.load(sys.stdin)['tunnels'] if t['public_url'].startswith('https://')), ''))")
[ -z "$URL" ] && { echo "ngrok не запущен"; exit 1; }
if grep -q "^PUBLIC_BASE_URL=" .env; then
  sed -i '' "s|^PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=$URL|" .env
else
  echo "PUBLIC_BASE_URL=$URL" >> .env
fi
echo "PUBLIC_BASE_URL = $URL"
docker compose -p infra -f docker-compose.yml -f docker-compose.share.yml \
  up -d --no-deps --force-recreate core-service notification-worker
