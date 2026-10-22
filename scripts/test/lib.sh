#!/usr/bin/env bash
# 테스트 스크립트가 함께 쓰는 설정. 직접 실행하지 않는다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

log() { printf '\n==> %s\n' "$*"; }

gradle() {
  ./gradlew --console=plain "$@"
}

# compose 클러스터(nginx 127.0.0.1:8000)가 요청을 받을 때까지 기다린다.
wait_for_cluster() {
  local url="${1:-http://127.0.0.1:8000}"
  for _ in $(seq 1 90); do
    if curl -fs -o /dev/null "$url/v3/api-docs"; then
      return 0
    fi
    sleep 2
  done
  echo "클러스터가 3분 안에 준비되지 않았습니다: $url" >&2
  return 1
}
