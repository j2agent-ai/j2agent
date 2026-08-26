#!/usr/bin/env sh
set -eu

case "${J2AGENT_ENFORCE_HTTPS:-false}" in
  true) template=/etc/nginx/http-templates/http-redirect.conf ;;
  false) template=/etc/nginx/http-templates/http-proxy.conf ;;
  *)
    echo "J2AGENT_ENFORCE_HTTPS must be true or false" >&2
    exit 1
    ;;
esac

sed \
  -e "s|__J2AGENT_PORT__|${J2AGENT_PORT}|g" \
  -e "s|__J2AGENT_NGINX_PORT__|${J2AGENT_NGINX_PORT}|g" \
  "$template" > /etc/nginx/conf.d/10-http.conf
