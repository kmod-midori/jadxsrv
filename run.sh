#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
escaped_args=()
for input_file in "$@"; do
    escaped_input="${input_file//\\/\\\\}"
    escaped_input="${escaped_input//\"/\\\"}"
    escaped_args+=("\"$escaped_input\"")
done

exec "$script_dir/gradlew" run --args="${escaped_args[*]}"
