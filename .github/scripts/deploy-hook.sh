#!/usr/bin/env bash
# Llama al gancho de avisos del back (/api/system-announcements/deploy-hook) por SSH contra localhost
# del servidor, así no depende de la URL pública ni del proxy.
#
# Uso: deploy-hook.sh <ANNOUNCE|STATUS|RESTARTING> [minutos]
# Salida: primera línea = código HTTP (000 si no hay token o el back no contesta); el resto = cuerpo JSON.
# Variables: HOST, SSH_USER, DEPLOY_HOOK_TOKEN, BACK_LOCAL_URL (por defecto http://localhost:8080).
set -uo pipefail

PHASE="${1:-STATUS}"
MINUTES=$(printf '%s' "${2:-0}" | tr -cd '0-9')
[ -z "$MINUTES" ] && MINUTES=0
URL="${BACK_LOCAL_URL:-http://localhost:8080}/api/system-announcements/deploy-hook"

if [ -z "${DEPLOY_HOOK_TOKEN:-}" ]; then
  echo "000"
  exit 0
fi

# El token va por stdin (curl -H @-) para que no quede en la línea de comandos del servidor.
printf 'X-Deploy-Token: %s\n' "$DEPLOY_HOOK_TOKEN" | \
  ssh -i ~/.ssh/deploy_key -o BatchMode=yes -o ConnectTimeout=15 "${SSH_USER}@${HOST}" \
    "rm -f /tmp/deploy-hook.json; \
     curl -s -m 10 -o /tmp/deploy-hook.json -w '%{http_code}' -H @- -H 'Content-Type: application/json' \
       -X POST -d '{\"phase\":\"${PHASE}\",\"minutes\":${MINUTES}}' '${URL}'; \
     echo; cat /tmp/deploy-hook.json 2>/dev/null; rm -f /tmp/deploy-hook.json" \
  || echo "000"
