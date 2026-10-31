# Saathi backend. The link sandbox (Playwright Chromium) only runs inside this container.
# The docker CLI is included so the backend can start `saathi-skill-sandbox` containers through the host socket
# (see deploy/README.md for the trust tradeoff).
FROM python:3.12-slim

ARG DOCKER_CLI_VERSION=27.5.1
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SANDBOX_ENABLED=1 \
    FIXTURES_DIR=/srv/fixtures \
    DATABASE_URL=sqlite:////data/saathi.db

WORKDIR /srv/backend
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" \
       | tar -xz -C /usr/local/bin --strip-components=1 docker/docker \
    && apt-get purge -y -qq curl && apt-get autoremove -y -qq && rm -rf /var/lib/apt/lists/*
COPY backend/pyproject.toml ./
COPY backend/app ./app
RUN pip install --no-cache-dir . \
    && playwright install --with-deps chromium \
    && useradd --create-home saathi && mkdir -p /data && chown saathi /data
COPY fixtures /srv/fixtures

USER saathi
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
