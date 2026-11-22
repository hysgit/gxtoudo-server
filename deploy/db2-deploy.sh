#!/usr/bin/env bash
set -euo pipefail
release="${1:?pass release version}"
port="${2:-18765}"
bind="${3:-10.10.10.38}"
[[ "$release" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]] || exit 2
[[ "$port" =~ ^[0-9]{4,5}$ ]] && ((port >= 1024 && port <= 65535)) || exit 2
[[ "$bind" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || exit 2
base=/home/hy/deploy/gxtudo-server
config="$base/storage.env"
push_secrets="$base/push-secrets"
push_mount=()
if [[ -d "$push_secrets" ]]; then
  push_mount=(--mount "type=bind,src=$push_secrets,dst=/run/gxtodo-push,readonly")
fi
ai_secrets="$base/ai-secrets"
ai_mount=()
if [[ -d "$ai_secrets" ]]; then
  ai_mount=(--mount "type=bind,src=$ai_secrets,dst=/run/gxtodo-ai,readonly")
fi
backup="$base/backups/$release"
image="gxtudo-server:$release"
rollback="gxtudo-server-rollback-$release"
mkdir -p "$backup"
chmod 700 "$base/backups" "$backup"
if [[ ! -f "$config" ]]; then
  umask 077
  database_password="$(openssl rand -hex 24)"
  storage_password="$(openssl rand -hex 24)"
  cat > "$config" <<ENV
GXTODO_DATABASE_URL=jdbc:postgresql://gxtudo-postgres:5432/gxtodo
GXTODO_DATABASE_USER=gxtodo
GXTODO_DATABASE_PASSWORD=$database_password
POSTGRES_DB=gxtodo
POSTGRES_USER=gxtodo
POSTGRES_PASSWORD=$database_password
GXTODO_MINIO_ENDPOINT=http://gxtudo-minio:9000
GXTODO_MINIO_ACCESS_KEY=gxtodo-media
GXTODO_MINIO_SECRET_KEY=$storage_password
MINIO_ROOT_USER=gxtodo-media
MINIO_ROOT_PASSWORD=$storage_password
MINIO_BROWSER=off
ENV
fi
chmod 600 "$config"
docker network inspect gxtudo-storage >/dev/null 2>&1 || docker network create gxtudo-storage >/dev/null
docker build --pull=false -t "$image" .
docker build --pull=false -f deploy/Dockerfile.minio -t gxtudo-minio:2025-10-15 .
if ! docker inspect gxtudo-postgres >/dev/null 2>&1; then
  docker run -d --name gxtudo-postgres --restart unless-stopped --network gxtudo-storage \
    --label com.gxtudo.managed=postgres --env-file "$config" \
    -v gxtudo-postgres-data:/var/lib/postgresql/data postgres:16-alpine >/dev/null
fi
if ! docker inspect gxtudo-minio >/dev/null 2>&1; then
  docker run -d --name gxtudo-minio --restart unless-stopped --network gxtudo-storage \
    --label com.gxtudo.managed=minio --env-file "$config" \
    -v gxtudo-minio-data:/data gxtudo-minio:2025-10-15 >/dev/null
fi
for attempt in $(seq 1 60); do
  if docker exec gxtudo-postgres pg_isready -U gxtodo -d gxtodo >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec gxtudo-postgres pg_isready -U gxtodo -d gxtodo >/dev/null
old=false
legacy=false
if docker inspect gxtudo-server >/dev/null 2>&1; then
  [[ "$(docker inspect --format '{{index .Config.Labels "com.gxtudo.managed"}}' gxtudo-server)" == java-server ]] || exit 3
  if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' gxtudo-server | grep -q '^GXTODO_DATABASE='; then legacy=true; fi
  old=true
fi
# Validate the new Java process and storage before interrupting the old API.
docker run -d --name "gxtudo-preflight-$release" --network gxtudo-storage --env-file "$config" \
  "${push_mount[@]}" "${ai_mount[@]}" -e GXTODO_PUSH_WORKER=false -e GXTODO_AI_WORKER=false "$image" >/dev/null
preflight=false
for attempt in $(seq 1 60); do
  if docker exec "gxtudo-preflight-$release" /app/healthcheck.sh >/dev/null 2>&1; then preflight=true; break; fi
  sleep 2
done
docker rm -f "gxtudo-preflight-$release" >/dev/null
[[ "$preflight" == true ]] || { echo 'New API preflight failed; old API remains active' >&2; exit 4; }
restore() {
  code=$?
  if ((code != 0)) && [[ "$old" == true ]]; then
    docker rm -f gxtudo-server >/dev/null 2>&1 || true
    docker rename "$rollback" gxtudo-server >/dev/null 2>&1 || true
    docker start gxtudo-server >/dev/null 2>&1 || true
    echo 'Release failed; previous API restarted. PostgreSQL and backup data retained.' >&2
  fi
  exit "$code"
}
if [[ "$old" == true ]]; then
  docker stop -t 30 gxtudo-server >/dev/null
  docker rename gxtudo-server "$rollback"
fi
trap restore EXIT
if [[ "$legacy" == true ]]; then
  # The old writer is stopped. Use SQLite backup API, including WAL, rather than copying a live file.
  docker run --rm -e BACKUP_UID="$(id -u)" -e BACKUP_GID="$(id -g)" \
    -v gxtudo-server-data:/legacy -v "$backup:/backup" python:3.9-slim \
    python -c "import os,sqlite3; s=sqlite3.connect('file:/legacy/gxtodo.sqlite3?mode=rw',uri=True); s.execute('pragma query_only=on'); d=sqlite3.connect('/backup/gxtodo.sqlite3'); s.backup(d); assert d.execute('pragma journal_mode=delete').fetchone()[0]=='delete'; assert d.execute('pragma integrity_check').fetchone()[0]=='ok'; d.close(); s.close(); os.chmod('/backup/gxtodo.sqlite3',0o600); os.chown('/backup/gxtodo.sqlite3',int(os.environ['BACKUP_UID']),int(os.environ['BACKUP_GID']))"
  sha256sum "$backup/gxtodo.sqlite3" > "$backup/SHA256SUMS"
  docker run --rm --network gxtudo-storage --env-file "$config" --user 0:0 -v "$backup:/migration:ro" \
    -e GXTODO_MIGRATE_SQLITE=/migration/gxtodo.sqlite3 "$image" --spring.main.web-application-type=none \
    > "$backup/migration.log" 2>&1
else
  docker exec gxtudo-postgres pg_dump -U gxtodo -d gxtodo -Fc > "$backup/gxtodo.dump"
  chmod 600 "$backup/gxtodo.dump"
  sha256sum "$backup/gxtodo.dump" > "$backup/SHA256SUMS"
fi
docker run -d --name gxtudo-server --restart unless-stopped --stop-timeout 30 \
  --label com.gxtudo.managed=java-server --label "com.gxtudo.version=$release" \
  --network gxtudo-storage --env-file "$config" "${push_mount[@]}" "${ai_mount[@]}" -p "$bind:$port:8765" "$image" >/dev/null
healthy=false
for attempt in $(seq 1 60); do
  if docker exec gxtudo-server /app/healthcheck.sh >/dev/null 2>&1; then healthy=true; break; fi
  sleep 2
done
[[ "$healthy" == true ]] || { docker logs --tail 60 gxtudo-server >&2; exit 5; }
trap - EXIT
printf 'Release: %s\nStorage: PostgreSQL + private MinIO\nBackup: %s\n' "$release" "$backup"
docker inspect --format '{{.Name}} {{.Image}}' gxtudo-server gxtudo-postgres gxtudo-minio
