#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-full}"
case "$mode" in
  full)
    exec ./mvnw -B -ntp verify
    ;;
  focused)
    shift || true
    if [[ $# -eq 0 ]]; then
      echo "usage: scripts/gate.sh focused <module>[:<test>]" >&2
      exit 2
    fi
    module="$1"
    exec ./mvnw -B -ntp -pl "$module" -am test
    ;;
  compile)
    exec ./mvnw -B -ntp compile
    ;;
  *)
    echo "usage: scripts/gate.sh full|compile|focused <module>" >&2
    exit 2
    ;;
esac
