# Saathi backend. The link sandbox (Playwright Chromium) only runs inside this container.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SANDBOX_ENABLED=1 \
    FIXTURES_DIR=/srv/fixtures \
    DATABASE_URL=sqlite:////data/saathi.db

WORKDIR /srv/backend
COPY backend/pyproject.toml ./
COPY backend/app ./app
RUN pip install --no-cache-dir . \
    && playwright install --with-deps chromium \
    && useradd --create-home saathi && mkdir -p /data && chown saathi /data
COPY fixtures /srv/fixtures

USER saathi
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
