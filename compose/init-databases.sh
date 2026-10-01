#!/bin/bash
# Creates one database and one role per service.
#
# Database per service is the point: a service can only reach its own tables, so none of them can
# quietly start reading another's schema and turn four deployments back into one. The ledger owns
# money, accounts owns identity, payments owns workflow state and risk owns the screening rules,
# and nothing in the stack can cross those lines with a join.
#
# They share one PostgreSQL *instance* rather than four, which is a local-development compromise
# and nothing more. Four containers would buy real failure isolation at a cost in laptop memory
# that this repository is not worth. Production would separate them; Phase 5 does exactly that.
#
# This runs only on an empty data directory, which is how the official image works. After pulling
# a change to this file against an existing stack:
#
#   docker compose down -v && docker compose up --build

set -euo pipefail

create_service_database() {
  local name="$1"
  echo "creating database and role: ${name}"
  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<-EOSQL
    CREATE ROLE ${name} WITH LOGIN PASSWORD '${name}';
    CREATE DATABASE ${name} OWNER ${name};
    GRANT ALL PRIVILEGES ON DATABASE ${name} TO ${name};
EOSQL
}

# The ledger database and role already exist: the image created them from POSTGRES_DB and
# POSTGRES_USER before this script ran.
for service in accounts payments risk; do
  create_service_database "${service}"
done

echo "service databases ready: ledger, accounts, payments, risk"
