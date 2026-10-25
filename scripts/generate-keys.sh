#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
KEYS="$ROOT/.local/keys"
umask 077
mkdir -p "$KEYS"
if [ -f "$KEYS/private.pem" ] && [ -f "$KEYS/public.pem" ]; then
    exit 0
fi
if [ -e "$KEYS/private.pem" ] || [ -e "$KEYS/public.pem" ]; then
    echo "Найдена неполная пара ключей в $KEYS. Восстановите оба файла или удалите неполную пару." >&2
    exit 1
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEYS/private.pem" 2>/dev/null
openssl pkey -in "$KEYS/private.pem" -pubout -out "$KEYS/public.pem"
echo "Ключи созданы в $KEYS"
