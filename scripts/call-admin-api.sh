#!/usr/bin/env bash
##
# Вызов защищённых HTTP-эндпоинтов с сервера/со стороны администратора
# БЕЗ явной передачи пароля в командной строке.
#
# Источники учётных данных (в порядке приоритета):
#   1. $ADMIN_USERNAME / $ADMIN_PASSWORD в environment
#   2. ~/.netrc (curl сам подхватит при флаге --netrc)
#   3. Интерактивный запрос (read -s)
#
# Использование:
#   ./scripts/call-admin-api.sh                    # /users на http://localhost:8081
#   ./scripts/call-admin-api.sh /healthcheck
#   BASE_URL=https://bot.example.com ./scripts/call-admin-api.sh /users
##
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
path="${1:-/users}"

if [[ -n "${ADMIN_USERNAME:-}" && -n "${ADMIN_PASSWORD:-}" ]]; then
    # curl читает -u из env/флага, не из argv
    curl -sS --fail-with-body -u "${ADMIN_USERNAME}:${ADMIN_PASSWORD}" "${BASE_URL}${path}"
elif [[ -f "$HOME/.netrc" ]]; then
    curl -sS --fail-with-body --netrc "${BASE_URL}${path}"
else
    read -r -p 'Admin username: ' u
    read -r -s -p 'Admin password: ' p
    echo
    curl -sS --fail-with-body -u "${u}:${p}" "${BASE_URL}${path}"
fi
echo
