#!/usr/bin/env bash
set -euo pipefail
version="${1:?pass a version}"
[[ "$version" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]] || exit 2
base=/home/hy/deploy/gxtudo-server
secret="$base/price-browser-secrets"
config="$base/storage.env"
image="gxtudo-price-browser:$version"
name=gxtudo-price-browser
volume=gxtudo-price-profiles
network=gxtudo-price
rollback="$name-rollback-$version"

test -f "$config"
docker build -f deploy/Dockerfile.price-browser -t "$image" .
docker network inspect "$network" >/dev/null 2>&1 || docker network create "$network" >/dev/null
docker volume inspect "$volume" >/dev/null 2>&1 || docker volume create "$volume" >/dev/null
umask 077
mkdir -p "$secret"
if [[ ! -s "$secret/token" ]]; then openssl rand -hex 32 > "$secret/token"; fi
docker run --rm --network none --user 0:0 --mount "type=bind,src=$secret,dst=/init-secret" \
  -v "$volume:/init-profiles" --entrypoint sh "$image" -c \
  'chown 10001:10001 /init-profiles; chmod 700 /init-profiles; chgrp 10001 /init-secret /init-secret/token; chmod 710 /init-secret; chmod 640 /init-secret/token'

previous=false
if docker inspect "$name" >/dev/null 2>&1; then
  [[ "$(docker inspect --format '{{index .Config.Labels "com.gxtudo.managed"}}' "$name")" == price-browser ]]
  docker stop -t 20 "$name" >/dev/null
  docker rename "$name" "$rollback"
  previous=true
fi
restore() {
  code=$?
  if ((code != 0)); then
    docker rm -f "$name" >/dev/null 2>&1 || true
    if [[ "$previous" == true ]]; then
      docker rename "$rollback" "$name"
      docker start "$name" >/dev/null
    fi
  fi
  exit "$code"
}
trap restore EXIT
docker run -d --name "$name" --network "$network" --restart unless-stopped --stop-timeout 20 \
  --label com.gxtudo.managed=price-browser --label "com.gxtudo.version=$version" \
  --cap-drop ALL --security-opt no-new-privileges --read-only --tmpfs /tmp:size=128m,mode=1777 \
  --memory 600m --pids-limit 160 --shm-size 128m \
  --mount "type=bind,src=$secret,dst=/run/gxtodo-price,readonly" -v "$volume:/data" \
  -e GXTODO_PRICE_BROWSER_TOKEN_FILE=/run/gxtodo-price/token "$image" >/dev/null
healthy=false
for attempt in $(seq 1 20); do
  if docker exec "$name" python -c "import urllib.request; assert urllib.request.urlopen('http://127.0.0.1:8790/health',timeout=2).status == 200" >/dev/null 2>&1; then
    healthy=true
    break
  fi
  sleep 1
done
[[ "$healthy" == true ]] || { docker logs --tail 30 "$name" >&2; exit 4; }
tmp="$(mktemp "$base/storage.env.price.XXXXXX")"
awk '!/^GXTODO_PRICE_BROWSER_(URL|TOKEN_FILE)=/' "$config" > "$tmp"
printf '%s\n' 'GXTODO_PRICE_BROWSER_URL=http://gxtudo-price-browser:8790' \
  'GXTODO_PRICE_BROWSER_TOKEN_FILE=/run/gxtodo-price/token' >> "$tmp"
cp -p "$config" "$base/storage.env.before-price-$version"
mv -f "$tmp" "$config"
trap - EXIT
printf 'Browser ready on private network %s using volume %s\n' "$network" "$volume"
