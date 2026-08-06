#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
secret_dir="$(cd "$script_dir/.." && pwd)/secrets"
key_file="$secret_dir/agent-master-key"

mkdir -p "$secret_dir"
chmod 700 "$secret_dir"

if [[ -e "$key_file" ]]; then
  echo "Agent master key already exists: $key_file"
  exit 0
fi

openssl rand -base64 32 > "$key_file"
chmod 600 "$key_file"
echo "Generated Agent master key: $key_file"
