#!/usr/bin/env bash
# Writes a compressed dump of the application's PostgreSQL database (compose.yaml's `postgres` service) to
# backups/stockresearch-<UTC timestamp>.dump. Restore it with:
#   docker compose exec -T postgres pg_restore -U stockresearch -d stockresearch --clean --if-exists < backups/<file>.dump
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p backups
file="backups/stockresearch-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T postgres pg_dump -U "${DB_USER:-stockresearch}" -d stockresearch -Fc > "$file"
echo "Wrote $file ($(du -h "$file" | cut -f1))"
