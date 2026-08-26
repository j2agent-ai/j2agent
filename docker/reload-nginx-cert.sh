#!/usr/bin/env sh
set -eu

docker compose exec -T nginx nginx -t
docker compose exec -T nginx nginx -s reload
