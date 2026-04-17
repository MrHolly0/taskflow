#!/usr/bin/env bash
set -euo pipefail

PORT=${SERVER_PORT:-8080}
SECRET=${TELEGRAM_WEBHOOK_SECRET:-}
BOT_TOKEN=${TELEGRAM_BOT_TOKEN:-}

if [[ -z "$BOT_TOKEN" ]]; then
  echo "TELEGRAM_BOT_TOKEN is not set" >&2
  exit 1
fi

ngrok http "$PORT" --log=stdout &
NGROK_PID=$!

sleep 2

NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | python3 -c "import sys,json; print(json.load(sys.stdin)['tunnels'][0]['public_url'])")
WEBHOOK_URL="${NGROK_URL}/api/telegram/webhook"

echo "ngrok URL: $NGROK_URL"
echo "Setting webhook: $WEBHOOK_URL"

curl -s "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
  -d "url=${WEBHOOK_URL}" \
  ${SECRET:+-d "secret_token=${SECRET}"} | python3 -m json.tool

echo ""
echo "Webhook set. Press Ctrl+C to stop ngrok."
wait "$NGROK_PID"