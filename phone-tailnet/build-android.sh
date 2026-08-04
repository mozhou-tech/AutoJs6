#!/usr/bin/env bash
set -euo pipefail

module_dir="$(cd "$(dirname "$0")" && pwd)"
output="${1:-$module_dir/../app/libs/phone-tailnet.aar}"

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
  exit 1
fi

mkdir -p "$(dirname "$output")"
cd "$module_dir"

export GOSUMDB="${GOSUMDB:-sum.golang.org}"
go mod download
go tool gomobile bind \
  -target=android/arm,android/arm64 \
  -androidapi=24 \
  -o "$output" \
  .
