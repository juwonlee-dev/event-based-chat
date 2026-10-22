#!/usr/bin/env bash
# 단위 테스트. Docker 없이 돈다. build도 같은 테스트를 거치므로 실패하면 빌드가 실패한다.
#   scripts/test/unit.sh
source "$(dirname "$0")/lib.sh"
log "단위 테스트"
gradle test "$@"
