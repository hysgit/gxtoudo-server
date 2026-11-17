#!/bin/sh
set -eu

wget -q -O - "http://127.0.0.1:${GXTODO_PORT:-8765}/health" | grep -Fq '"status":"ok"'
