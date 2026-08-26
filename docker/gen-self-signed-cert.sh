#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VOLUMES_PATH="${J2AGENT_VOLUMES_PATH:-${SCRIPT_DIR}}"
DIR_PATH_SEPARATOR="${DIR_PATH_SEPARATOR:-/}"
CERT_DIR="${VOLUMES_PATH}${DIR_PATH_SEPARATOR}volumes${DIR_PATH_SEPARATOR}nginx${DIR_PATH_SEPARATOR}certs"
CERT_FILE="${J2AGENT_HTTPS_CERT_FILE:-j2agent.crt}"
KEY_FILE="${J2AGENT_HTTPS_KEY_FILE:-j2agent.key}"
DAYS="${J2AGENT_HTTPS_CERT_DAYS:-3650}"

if [ "$#" -gt 0 ]; then
  HOSTS="$*"
else
  HOSTS="${J2AGENT_HTTPS_CERT_HOSTS:-localhost 127.0.0.1}"
fi

mkdir -p "$CERT_DIR"

SAN=""
CN=""
for host in $HOSTS; do
  if [ -z "$CN" ]; then
    CN="$host"
  fi
  case "$host" in
    *[!0-9.]*)
      SAN="${SAN}DNS:${host},"
      ;;
    *)
      SAN="${SAN}IP:${host},"
      ;;
  esac
done

SAN=${SAN%,}

openssl req -x509 -nodes -newkey rsa:2048 \
  -days "$DAYS" \
  -keyout "${CERT_DIR}/${KEY_FILE}" \
  -out "${CERT_DIR}/${CERT_FILE}" \
  -subj "/CN=${CN}" \
  -addext "subjectAltName=${SAN}"

chmod 600 "${CERT_DIR}/${KEY_FILE}"
chmod 644 "${CERT_DIR}/${CERT_FILE}"

printf 'Generated self-signed certificate:\n'
printf '  cert: %s\n' "${CERT_DIR}/${CERT_FILE}"
printf '  key:  %s\n' "${CERT_DIR}/${KEY_FILE}"
printf '  SAN:  %s\n' "${SAN}"
