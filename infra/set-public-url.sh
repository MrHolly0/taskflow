#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

python3 - "${1:-}" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlsplit

url = sys.argv[1].rstrip('/')
parsed = urlsplit(url)
if parsed.scheme != 'https' or not parsed.netloc or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
    sys.exit('Укажи публичный HTTPS-адрес туннеля без пути и параметров.')
path = Path('.env')
lines = path.read_text().splitlines()
updated = False
for index, line in enumerate(lines):
    if line.startswith('PUBLIC_BASE_URL='):
        lines[index] = 'PUBLIC_BASE_URL=' + url
        updated = True
if not updated:
    lines.append('PUBLIC_BASE_URL=' + url)
path.write_text('\n'.join(lines) + '\n')
print('PUBLIC_BASE_URL = ' + url)
PY

docker compose -p infra -f docker-compose.yml -f docker-compose.share.yml \
  up -d --no-deps --force-recreate core-service notification-worker
docker compose -p infra -f docker-compose.yml -f docker-compose.share.yml restart nginx
