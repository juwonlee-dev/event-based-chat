#!/usr/bin/env bash
# 모든 테스트를 차례로 돌리고 결과를 모아 보여 준다. 하나라도 실패하면 종료 코드 1.
#   scripts/test/run-all.sh                   # 단위 → 통합 → 클러스터(장애 주입 포함)
#   scripts/test/run-all.sh --skip-cluster    # 단위 → 통합
#   scripts/test/run-all.sh --obs             # 클러스터 단계에 트레이스 검사 추가
# 클러스터 단계는 끝나면 클러스터를 내린다.
dir="$(cd "$(dirname "$0")" && pwd)"
skip_cluster=false
cluster_args=(--faults --down)
for arg in "$@"; do
  case "$arg" in
    --skip-cluster) skip_cluster=true ;;
    --obs) cluster_args+=(--obs) ;;
    *) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
  esac
done

names=() results=() times=()
run() {
  local name="$1"; shift
  local start=$SECONDS
  if "$@"; then results+=("PASS"); else results+=("FAIL"); fi
  names+=("$name")
  times+=("$((SECONDS - start))s")
}

run "단위" bash "$dir/unit.sh"
run "통합" bash "$dir/integration.sh"
$skip_cluster || run "클러스터" bash "$dir/cluster.sh" "${cluster_args[@]}"

echo
echo "결과"
failed=0
for i in "${!names[@]}"; do
  printf '  %-4s %-8s %s\n' "${results[$i]}" "${names[$i]}" "${times[$i]}"
  [ "${results[$i]}" = "PASS" ] || failed=1
done
echo "보고서: chat-*/build/reports/tests/<스위트>/index.html"
exit $failed
