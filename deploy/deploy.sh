#!/usr/bin/env bash
# Deploy the current working tree to the Saathi Droplet and (re)start the stack.
# Works from Git Bash on Windows, macOS and Linux (tar over ssh; no rsync needed).
#   bash deploy/deploy.sh            # upload, build, restart
#   SAATHI_HOST=root@1.2.3.4 bash deploy/deploy.sh
set -euo pipefail

HOST="${SAATHI_HOST:-root@165.245.196.218}"
DOMAIN="${SAATHI_DOMAIN:-165-245-196-218.sslip.io}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=15 "$HOST")

[ -f "$ROOT/.env" ] || { echo "error: $ROOT/.env not found (copy .env.example and fill in keys)" >&2; exit 1; }

echo "==> Uploading source to $HOST:/opt/saathi/src"
"${SSH[@]}" 'mkdir -p /opt/saathi && rm -rf /opt/saathi/src.new && mkdir -p /opt/saathi/src.new'
tar -C "$ROOT" -czf - \
  --exclude=.git --exclude=.venv --exclude=android --exclude=build --exclude='*/build' \
  --exclude='*.db' --exclude=.env --exclude=docs/demo --exclude=__pycache__ --exclude=.pytest_cache \
  --exclude=.ruff_cache --exclude=.claude --exclude=node_modules \
  backend fixtures deploy docs scripts README.md \
  | "${SSH[@]}" 'tar -xzf - -C /opt/saathi/src.new && rm -rf /opt/saathi/src && mv /opt/saathi/src.new /opt/saathi/src'

echo "==> Uploading .env (production overrides applied, chmod 600)"
grep -vE '^(PUBLIC_BASE_URL|SANDBOX_ENABLED|MOCK|DATABASE_URL)=' "$ROOT/.env" \
  | tr -d '\r' \
  | { cat; printf 'PUBLIC_BASE_URL=https://%s\nSANDBOX_ENABLED=1\nMOCK=0\nDATABASE_URL=sqlite:////data/saathi.db\n' "$DOMAIN"; } \
  | "${SSH[@]}" 'umask 077 && cat > /opt/saathi/.env && chmod 600 /opt/saathi/.env'

echo "==> Building sandbox image, then backend + caddy"
"${SSH[@]}" bash -s <<'REMOTE'
set -euo pipefail
cd /opt/saathi/src/backend/sandbox_image && docker build -q -t saathi-skill-sandbox . >/dev/null
cd /opt/saathi/src/deploy
export DOCKER_GID="$(getent group docker | cut -d: -f3)"
docker compose --project-directory /opt/saathi/src/deploy up -d --build --remove-orphans
docker image prune -f >/dev/null
docker compose ps
REMOTE

echo "==> Waiting for https://$DOMAIN/health"
for _ in $(seq 1 60); do
  if curl -fsS --max-time 5 "https://$DOMAIN/health" >/dev/null 2>&1; then
    curl -fsS "https://$DOMAIN/health" | head -c 200; echo
    echo "Deployed: https://$DOMAIN"
    exit 0
  fi
  sleep 5
done
echo "error: backend did not become healthy; check: ssh $HOST 'cd /opt/saathi/src/deploy && docker compose logs --tail 80'" >&2
exit 1
