#!/usr/bin/env bash
# Load DB settings from an application env file WITHOUT executing it.
#
# Why this exists: `set -a; . ./backend/.env` looks harmless but is not. JDBC URLs are written
# unquoted and contain `&`, which bash parses as the background operator. Sourcing
#
#   SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/hms_production?createDatabaseIfNotExist=true&useSSL=false
#
# runs three background jobs and leaves SPRING_DATASOURCE_URL *empty* in the caller. backup.sh and
# restore.sh then fell back to a hardcoded database name and targeted the wrong schema. Spring reads
# the same file with its own parser (me.paulschwarz:spring-dotenv), so the app is unaffected and the
# breakage stays invisible until a dump fails — which is exactly how it went unnoticed.
#
# Usage:  . scripts/db/load-env.sh   # then:  hms_load_env [env_file]
#   env_file default: $ENV_FILE, else the first of backend/.env or .env that exists, relative to $PWD.
# Returns 0 if a file was loaded (path in $HMS_ENV_FILE), 1 if none was found.
#
# Only DB-related keys are exported, and never over a value the caller already set — so explicit
# MYSQL_* environment variables keep priority, as documented in backup.sh.

hms_load_env() {
  local f="${1:-${ENV_FILE:-}}" cand line key val
  if [ -z "$f" ]; then
    for cand in backend/.env .env; do
      if [ -f "$cand" ]; then f="$cand"; break; fi
    done
  fi
  if [ -z "$f" ] || [ ! -f "$f" ]; then return 1; fi

  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"                        # tolerate CRLF files
    line="${line#"${line%%[![:space:]]*}"}"     # strip leading whitespace
    case "$line" in '' | '#'*) continue ;; esac
    line="${line#export }"
    case "$line" in *=*) ;; *) continue ;; esac
    key="${line%%=*}"
    val="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"        # strip trailing whitespace from the key
    case "$val" in                              # strip one layer of matching quotes
      \"*\") val="${val#\"}"; val="${val%\"}" ;;
      \'*\') val="${val#\'}"; val="${val%\'}" ;;
    esac
    case "$key" in
      SPRING_DATASOURCE_URL | SPRING_DATASOURCE_USERNAME | SPRING_DATASOURCE_PASSWORD | MYSQL_HOST | MYSQL_PORT | MYSQL_USER | MYSQL_PASSWORD | MYSQL_DATABASE)
        if [ -z "${!key:-}" ]; then export "$key=$val"; fi
        ;;
    esac
  done < "$f"

  HMS_ENV_FILE="$f"
  export HMS_ENV_FILE
  return 0
}
