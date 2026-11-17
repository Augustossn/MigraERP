#!/usr/bin/env bash
# Executado pela imagem na primeira inicialização do volume.
sqlplus -L -S "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" @/migraerp-db/install.sql < /dev/null || exit 1
touch /opt/oracle/oradata/.migraerp-ready
