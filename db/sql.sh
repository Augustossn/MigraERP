#!/usr/bin/env bash
exec sqlplus -L -S "${APP_USER}/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1" "$@"
