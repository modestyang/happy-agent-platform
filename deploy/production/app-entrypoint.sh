#!/bin/sh
set -eu

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

validate_file() {
  file_label=$1
  file_path=$2

  if [ -z "$file_path" ] || [ ! -f "$file_path" ] || [ ! -r "$file_path" ] || [ ! -s "$file_path" ]; then
    fail "$file_label must name a readable, non-empty file"
  fi
}

read_password_file() {
  file_label=$1
  file_path=$2

  validate_file "$file_label" "$file_path"

  file_bytes=$(wc -c < "$file_path" | tr -d '[:space:]')
  last_two_bytes=$(tail -c 2 "$file_path" | LC_ALL=C od -An -tx1 -v | tr -d '[:space:]')
  case "$last_two_bytes" in
    0d0a) content_bytes=$((file_bytes - 2)) ;;
    *)
      last_byte=$(tail -c 1 "$file_path" | LC_ALL=C od -An -tx1 -v | tr -d '[:space:]')
      case "$last_byte" in
        0a | 0d) content_bytes=$((file_bytes - 1)) ;;
        *) content_bytes=$file_bytes ;;
      esac
      ;;
  esac

  if [ "$content_bytes" -le 0 ]; then
    fail "$file_label must contain a password"
  fi

  if head -c "$content_bytes" "$file_path" | LC_ALL=C od -An -tx1 -v | grep -Eq '(^|[[:space:]])(00|0a|0d)([[:space:]]|$)'; then
    fail "$file_label must not contain NUL bytes or embedded newlines"
  fi

  head -c "$content_bytes" "$file_path"
}

: "${FITNESS_DB_PASSWORD_FILE:?FITNESS_DB_PASSWORD_FILE is required}"
: "${AGENT_DB_PASSWORD_FILE:?AGENT_DB_PASSWORD_FILE is required}"
: "${HAPPY_AGENT_MASTER_KEY_FILE:?HAPPY_AGENT_MASTER_KEY_FILE is required}"

validate_file "HAPPY_AGENT_MASTER_KEY_FILE" "$HAPPY_AGENT_MASTER_KEY_FILE"
FITNESS_DB_PASSWORD=$(read_password_file "FITNESS_DB_PASSWORD_FILE" "$FITNESS_DB_PASSWORD_FILE")
AGENT_DB_PASSWORD=$(read_password_file "AGENT_DB_PASSWORD_FILE" "$AGENT_DB_PASSWORD_FILE")
export FITNESS_DB_PASSWORD AGENT_DB_PASSWORD
unset FITNESS_DB_PASSWORD_FILE AGENT_DB_PASSWORD_FILE

exec java "$@" -jar /app/app.jar
