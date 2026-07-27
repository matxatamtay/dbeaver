#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
upstream=${DBEAVER_UPSTREAM:-"$default_upstream"}
gson=$(find "$HOME/.m2/repository" -type f -path '*/com/google/code/gson/gson/*/gson-*.jar' | sort -V | tail -1)
[[ -n "$gson" ]] || { echo "Gson jar not found" >&2; exit 1; }
work="$repo_root/.work/tests"
rm -rf "$work"
mkdir -p "$work/mcp" "$work/studio" "$work/adapters"

mcp_classes="$upstream/plugins/org.jkiss.dbeaver.mcp/target/classes"
studio_classes="$upstream/plugins/org.jkiss.dbeaver.teststudio.core/target/classes"
ai_classes="$upstream/plugins/org.jkiss.dbeaver.teststudio.ai/target/classes"
pg_classes="$upstream/plugins/org.jkiss.dbeaver.teststudio.db.postgresql/target/classes"
mysql_classes="$upstream/plugins/org.jkiss.dbeaver.teststudio.db.mysql/target/classes"
sqlite_classes="$upstream/plugins/org.jkiss.dbeaver.teststudio.db.sqlite/target/classes"

mapfile -t mcp_tests < <(find "$repo_root/bundles/org.jkiss.dbeaver.mcp/test" -name '*Test.java' -print | sort)
if ((${#mcp_tests[@]})); then
  javac --release 21 -cp "$mcp_classes:$gson" -d "$work/mcp" "${mcp_tests[@]}"
  for source in "${mcp_tests[@]}"; do
    class=$(basename "$source" .java)
    java -cp "$mcp_classes:$work/mcp:$gson" "org.jkiss.dbeaver.mcp.$class"
  done
fi

mapfile -t studio_tests < <(find "$repo_root/bundles/org.jkiss.dbeaver.teststudio.core/test" -name '*Test.java' -print | sort)
javac --release 21 -cp "$studio_classes:$gson" -d "$work/studio" "${studio_tests[@]}"
for source in "${studio_tests[@]}"; do
  class=$(basename "$source" .java)
  java -cp "$studio_classes:$work/studio:$gson" "org.jkiss.dbeaver.teststudio.core.$class"
done

adapter_cp="$studio_classes:$ai_classes:$pg_classes:$mysql_classes:$sqlite_classes:$gson"
mapfile -t adapter_tests < <(find "$repo_root/integration-tests/unit" -name '*Test.java' -print | sort)
javac --release 21 -cp "$adapter_cp" -d "$work/adapters" "${adapter_tests[@]}"
for source in "${adapter_tests[@]}"; do
  class=$(basename "$source" .java)
  java -cp "$adapter_cp:$work/adapters" "org.jkiss.dbeaver.teststudio.tests.$class"
done

printf 'Standalone tests passed: MCP=%d Studio=%d Adapter/AI=%d\n' "${#mcp_tests[@]}" "${#studio_tests[@]}" "${#adapter_tests[@]}"
