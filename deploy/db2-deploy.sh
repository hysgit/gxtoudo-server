#!/usr/bin/env bash
set -euo pipefail

release_version="${1:?pass the release version}"
container_port="${2:-18765}"
bind_address="${3:-10.10.10.38}"
if ! [[ "$release_version" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]]; then
  echo "Invalid release version" >&2
  exit 2
fi
if ! [[ "$container_port" =~ ^[0-9]{4,5}$ ]] || ((container_port < 1024 || container_port > 65535)); then
  echo "Invalid service port" >&2
  exit 2
fi
if ! [[ "$bind_address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  echo "Invalid IPv4 address" >&2
  exit 2
fi

release="${release_version#v}"
image="gxtudo-server:$release"
volume="gxtudo-server-data"
container="gxtudo-server"

docker build --pull=false --tag "$image" --label "com.gxtudo.version=$release" .
docker volume inspect "$volume" >/dev/null 2>&1 || docker volume create "$volume" >/dev/null

if docker inspect "$container" >/dev/null 2>&1; then
  managed="$(docker inspect --format '{{ index .Config.Labels "com.gxtudo.managed" }}' "$container")"
  if [[ "$managed" != "java-server" ]]; then
    echo "The existing container is not managed by this deployment" >&2
    exit 3
  fi
  docker rm --force "$container" >/dev/null
fi

docker run --detach --name "$container" --restart unless-stopped --stop-timeout 30 \
  --label com.gxtudo.managed=java-server \
  --label "com.gxtudo.version=$release" \
  --publish "$bind_address:$container_port:8765" \
  --volume "$volume:/data" \
  --env GXTODO_DATABASE=/data/gxtodo.sqlite3 \
  --env GXTODO_BIND=0.0.0.0 \
  --env GXTODO_PORT=8765 \
  "$image" >/dev/null

healthy=false
for attempt in $(seq 1 60); do
  status="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container" 2>/dev/null || true)"
  if [[ "$status" == "running healthy" ]]; then
    healthy=true
    break
  fi
  if [[ "$status" == exited* || "$status" == dead* ]]; then
    break
  fi
  sleep 2
done

if [[ "$healthy" != true ]]; then
  docker logs --tail 80 "$container" >&2 || true
  exit 4
fi

echo "Container: $container"
echo "Image: $image"
echo "API port: $container_port"
echo "Database volume: $volume"
echo "Database: /data/gxtodo.sqlite3 in volume $volume"
docker inspect --format 'Health: {{.State.Health.Status}}' "$container"
