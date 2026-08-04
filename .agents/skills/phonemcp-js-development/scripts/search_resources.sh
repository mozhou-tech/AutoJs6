#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <query> [all|examples|api|design]" >&2
  exit 2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
fi

query=$1
scope=${2:-all}

case "$scope" in
  all|examples|api|design) ;;
  *) usage ;;
esac

if ! command -v rg >/dev/null 2>&1; then
  echo "Error: ripgrep (rg) is required." >&2
  exit 1
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null) || {
  echo "Error: skill is not inside a Git repository." >&2
  exit 1
}

examples_dir="$repo_root/app/src/main/assets-app/sample"
api_dir="$repo_root/app/src/main/assets-app/docs"
design_dir="$repo_root/docs"
found=0

search_tree() {
  local label=$1
  local directory=$2
  local glob=$3
  local name_matches

  if [[ ! -d "$directory" ]]; then
    echo "Warning: missing $directory" >&2
    return
  fi

  echo "$label"
  name_matches=$(rg --files --glob "$glob" "$directory" | rg --ignore-case --fixed-strings -- "$query" || true)
  if [[ -n "$name_matches" ]]; then
    echo "Filename matches:"
    echo "$name_matches"
    found=1
  fi

  echo "Content matches:"
  if rg --line-number --ignore-case --fixed-strings --glob "$glob" -- "$query" "$directory"; then
    found=1
  else
    local status=$?
    if [[ $status -ne 1 ]]; then
      exit "$status"
    fi
    echo "(no matches)"
  fi
  echo
}

if [[ "$scope" == all || "$scope" == examples ]]; then
  search_tree "JavaScript examples" "$examples_dir" '*.js'
fi

if [[ "$scope" == all || "$scope" == api ]]; then
  search_tree "HTML API documentation" "$api_dir" '*.html'
fi

if [[ "$scope" == all || "$scope" == design ]]; then
  search_tree "Project design documentation" "$design_dir" '*.md'
fi

if [[ $found -eq 0 ]]; then
  exit 1
fi
