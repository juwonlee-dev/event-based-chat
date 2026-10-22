#!/usr/bin/env bash
# 통합 테스트. Testcontainers가 PostgreSQL·Redis·Kafka를 띄우므로 Docker만 있으면 된다(compose 불필요).
#   scripts/test/integration.sh                 # 전체(약 3분)
#   scripts/test/integration.sh RealtimeIT      # 클래스 하나
#   scripts/test/integration.sh 'Restore*'      # 이름 패턴
source "$(dirname "$0")/lib.sh"
args=()
for pattern in "$@"; do
  args+=(--tests "*$pattern")
done
log "통합 테스트 ${*:-(전체)}"
gradle :chat-api:integrationTest "${args[@]}"
