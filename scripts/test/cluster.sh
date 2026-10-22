#!/usr/bin/env bash
# 클러스터 테스트. compose로 앱 여러 대 + nginx를 띄우고(이미 떠 있으면 그대로 쓴다) 그 위에서 검사한다.
#   scripts/test/cluster.sh              # 노드 간 전달만
#   scripts/test/cluster.sh --faults     # + 앱 강제 종료·일시 정지·정상 종료(약 2분 더)
#   scripts/test/cluster.sh --obs        # + Tempo 트레이스(관측 스택을 함께 띄운다)
#   scripts/test/cluster.sh --down       # 끝나면 클러스터를 내린다(볼륨은 남긴다)
# 같은 데이터베이스를 쓰므로 한 번 띄운 클러스터에서 여러 번 돌려도 된다.
source "$(dirname "$0")/lib.sh"

faults=false obs=false down=false
for arg in "$@"; do
  case "$arg" in
    --faults) faults=true ;;
    --obs) obs=true ;;
    --down) down=true ;;
    *) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
  esac
done

tags="none()"
$faults && tags="$tags | fault"
$obs && tags="$tags | obs"

compose=(docker compose -f compose.yaml -f compose.cluster.yaml)
if $obs; then
  export OTEL_ENABLED=true
  compose+=(--profile obs)
fi

log "클러스터 기동(app 3대 + nginx)"
"${compose[@]}" up -d --build --scale app=3
wait_for_cluster

status=0
log "클러스터 테스트 (태그: $tags)"
gradle :chat-api:clusterTest "-Pcluster.tags=$tags" || status=$?

if $down; then
  log "클러스터 종료"
  "${compose[@]}" down
fi
exit $status
