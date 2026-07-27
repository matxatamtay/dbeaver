#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
product=${PRODUCT_DIR:-${1:-}}
[[ -n "$product" && -x "$product/dbeaver" ]] || { echo "PRODUCT_DIR with a DBeaver launcher is required" >&2; exit 2; }
product=$(cd "$product" && pwd)
port=${DBEAVER_MCP_PORT:-3853}
workspace=${TESTSTUDIO_WORKSPACE:-"$repo_root/.integration-workspace/policy-$port"}
log=${TESTSTUDIO_RUNTIME_LOG:-"/tmp/dbeaver-teststudio-policy-$port.log"}
pidfile=/tmp/dbeaver-teststudio-policy-$port.pid
rm -rf "$workspace"
stop() {
  local listener launcher
  listener=$(fuser -n tcp "$port" 2>/dev/null | awk '{print $1}' || true)
  launcher=$(cat "$pidfile" 2>/dev/null || true)
  [[ -n "$listener" ]] && kill "$listener" 2>/dev/null || true
  [[ -n "$launcher" ]] && kill "$launcher" 2>/dev/null || true
  sleep 2
  listener=$(fuser -n tcp "$port" 2>/dev/null | awk '{print $1}' || true)
  [[ -n "$listener" ]] && kill -9 "$listener" 2>/dev/null || true
}
trap stop EXIT
stop
launcher=("$product/dbeaver")
if [[ -z ${DISPLAY:-} && -z ${WAYLAND_DISPLAY:-} ]]; then launcher=(xvfb-run -a "$product/dbeaver"); fi
nohup env DBEAVER_MCP_ENABLED=true DBEAVER_MCP_PORT="$port" DBEAVER_MCP_SCOPES=observe,test \
  "${launcher[@]}" -data "$workspace" -clean -consoleLog >"$log" 2>&1 < /dev/null &
echo $! >"$pidfile"
for _ in $(seq 1 90); do curl -fsS "http://127.0.0.1:$port/healthz" >/tmp/teststudio-policy-health.json 2>/dev/null && break; sleep 1; done
curl -fsS "http://127.0.0.1:$port/healthz" >/tmp/teststudio-policy-health.json
rpc() {
  curl -sS --max-time 60 -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    --data "$1" "http://127.0.0.1:$port/mcp" >"$2"
}
rpc '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"dbeaver_teststudio","arguments":{"action":"validate_plan","arguments":{"plan":{"schema_version":"1.0","id":"policy-smoke","name":"Policy smoke","targets":{"default":{"connection":"placeholder"}},"steps":[{"id":"health","type":"query","sql":"SELECT 1"}]}}}}}' /tmp/teststudio-policy-allowed.json
rpc '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"dbeaver_teststudio","arguments":{"action":"create_plan","arguments":{"project":"General","plan":{"schema_version":"1.0","id":"blocked","name":"Blocked","targets":{"default":{"connection":"placeholder"}},"steps":[{"id":"health","type":"query","sql":"SELECT 1"}]}}}}}' /tmp/teststudio-policy-blocked.json
rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"dbeaver_workbench","arguments":{"action":"state","arguments":{}}}}' /tmp/teststudio-policy-ui-blocked.json
python3 - <<'PY'
import json
health=json.load(open('/tmp/teststudio-policy-health.json'))
allowed=json.load(open('/tmp/teststudio-policy-allowed.json'))['result']
blocked=json.load(open('/tmp/teststudio-policy-blocked.json'))
ui=json.load(open('/tmp/teststudio-policy-ui-blocked.json'))
assert health['policy']['allowed_scopes']==['observe','test'],health
assert not allowed.get('isError',False),allowed
assert allowed['structuredContent']['valid'] is True,allowed
assert blocked.get('error',{}).get('code')==-32001,blocked
assert ui.get('error',{}).get('code')==-32001,ui
print({'allowed_scopes':health['policy']['allowed_scopes'],'validate_plan':True,'workspace_denied':blocked['error']['code'],'ui_denied':ui['error']['code']})
PY
printf 'Restricted policy smoke passed on port %s\n' "$port"
