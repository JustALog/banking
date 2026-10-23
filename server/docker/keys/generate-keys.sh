#!/usr/bin/env bash
# Generates the RS256 keypair shared by all services (auth signs, the rest verify).
# Keys are gitignored; run once before starting the services:
#   bash docker/keys/generate-keys.sh
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -f "$DIR/jwt-private.pem" ]; then
  echo "Keys already exist in $DIR — delete them first to regenerate."
  exit 0
fi

openssl genrsa -out "$DIR/jwt-private.pem" 2048
openssl rsa -in "$DIR/jwt-private.pem" -pubout -out "$DIR/jwt-public.pem"

echo "Generated:"
echo "  $DIR/jwt-private.pem  (auth-service signs with this)"
echo "  $DIR/jwt-public.pem   (all services verify with this)"
