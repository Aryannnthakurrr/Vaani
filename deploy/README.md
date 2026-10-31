# Deploying Saathi (DigitalOcean Droplet)

**Server:** `root@165.245.196.218`: Ubuntu 24.04, 4 vCPU, 8 GB RAM, Bangalore region.
**Public URL:** https://165-245-196-218.sslip.io (automatic Let's Encrypt HTTPS through Caddy).

## What runs there

| Piece | Details |
|---|---|
| Docker Engine + compose plugin | From Docker's official apt repo |
| Firewall (ufw) | Only 22, 80 and 443 are open. Unattended security upgrades are on |
| `saathi-backend` | FastAPI backend built from `backend/Dockerfile`. Runs as an unprivileged user and keeps its SQLite DB in the `saathi-data` volume (`/data`). Limited to 2 GB |
| `caddy` | HTTPS reverse proxy for HTTP and WebSockets (`/live/*`), with no read timeout for long voice sessions |
| `saathi-skill-sandbox` image | Runs compiled Python skills. The backend starts one locked-down container per job (see `docs/LEARNING.md`) |

Files on the server:
- `/opt/saathi/src`: the uploaded source tree
- `/opt/saathi/.env`: the secrets, `chmod 600`, never in git

## Deploy or redeploy

From the repo root (Git Bash on Windows works):

```bash
bash deploy/deploy.sh
```

The script:
1. Tars the source tree (no `.git`, `.venv`, `android`, `.env`, databases or demo videos) over SSH.
2. Uploads `.env` with production overrides: `PUBLIC_BASE_URL=https://165-245-196-218.sslip.io`, `MOCK=0`,
   `SANDBOX_ENABLED=1` and the `/data` database path.
3. Rebuilds the sandbox image.
4. Runs `docker compose up -d --build`.
5. Waits for `/health`.

Logs and status:

```bash
ssh root@165.245.196.218 'cd /opt/saathi/src/deploy && docker compose ps && docker compose logs --tail 100 backend'
```

## Point the app at it

In the app, open Settings → Connection → Backend URL, enter `https://165-245-196-218.sslip.io` and tap Save.

## Things to know

- **TLS chain.** Caddy requests an RSA certificate on the `YR1 → Root YR → ISRG Root X1` chain. The default ECDSA
  chain ends at Let's Encrypt's new Root YE (its X2 cross-sign has expired), which older Android versions, Windows
  and older `certifi` bundles don't trust.
- **Networks that intercept TLS.** Some campus and office networks run a firewall such as FortiGate that re-signs
  HTTPS traffic. On those networks, clients see an untrusted Fortinet certificate. Test from mobile data, or from the
  server itself (`curl https://165-245-196-218.sslip.io/health`).
- **Trust tradeoff: Docker socket.** The backend container mounts `/var/run/docker.sock` so it can start sandbox
  containers. Anything that can talk to that socket is effectively root on the host. Today that is only backend code,
  never compiled skills: skills run with `--network none --read-only --memory 128m --pids-limit 32 --cap-drop ALL
  --user 65534`, and none of those containers mount the socket. To harden this, put a small sandbox broker (or rootless
  Docker / gVisor `runsc`) between the backend and the socket, allowing only `run saathi-skill-sandbox` with those exact
  flags.
- **One Telegram poller per bot token.** If a dev backend on a laptop and this server both run with the same
  `TELEGRAM_BOT_TOKEN`, Telegram rejects one of them with `409 Conflict`. Clear the token in the local `.env` while the
  server is live.
