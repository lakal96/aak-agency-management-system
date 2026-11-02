#!/bin/sh
# Runs inside the certbot container after a certificate is issued or renewed.
# Publishes the new cert where nginx already reads it (/etc/nginx/certs), so the nginx config does
# not change between the self-signed bootstrap cert and the Let's Encrypt one. nginx picks it up on
# its periodic reload (see the nginx service command in docker-compose.yml).
set -eu
cp "$RENEWED_LINEAGE/fullchain.pem" /etc/nginx/certs/cert.pem
cp "$RENEWED_LINEAGE/privkey.pem" /etc/nginx/certs/key.pem
chmod 644 /etc/nginx/certs/cert.pem
chmod 600 /etc/nginx/certs/key.pem
