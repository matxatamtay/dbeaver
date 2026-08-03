#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
product=${PRODUCT_DIR:-${1:-}}
[[ -n "$product" && -x "$product/dbeaver" ]] || { echo "PRODUCT_DIR with a DBeaver launcher is required" >&2; exit 2; }
product=$(cd "$product" && pwd)
port=${DBEAVER_MCP_PORT:-3851}
workspace=${TESTSTUDIO_WORKSPACE:-"$repo_root/.integration-workspace/ci-runtime-$port"}
log=${TESTSTUDIO_RUNTIME_LOG:-"/tmp/dbeaver-teststudio-runtime-$port.log"}
pidfile=${TESTSTUDIO_PID_FILE:-"/tmp/dbeaver-teststudio-runtime-$port.pid"}
rm -rf "$workspace"
mkdir -p "$workspace"

stop_runtime() {
  local listener launcher
  listener=$(fuser -n tcp "$port" 2>/dev/null | awk '{print $1}' || true)
  launcher=$(cat "$pidfile" 2>/dev/null || true)
  [[ -n "$listener" ]] && kill "$listener" 2>/dev/null || true
  [[ -n "$launcher" ]] && kill "$launcher" 2>/dev/null || true
  sleep 2
  listener=$(fuser -n tcp "$port" 2>/dev/null | awk '{print $1}' || true)
  [[ -n "$listener" ]] && kill -9 "$listener" 2>/dev/null || true
  [[ -n "$launcher" ]] && kill -9 "$launcher" 2>/dev/null || true
}
trap stop_runtime EXIT
stop_runtime

if [[ -n ${DISPLAY:-} || -n ${WAYLAND_DISPLAY:-} ]]; then
  nohup env DBEAVER_MCP_ENABLED=true DBEAVER_MCP_PORT="$port" \
    "$product/dbeaver" -data "$workspace" -clean -consoleLog >"$log" 2>&1 < /dev/null &
else
  command -v xvfb-run >/dev/null || { echo "DISPLAY is unavailable and xvfb-run is not installed" >&2; exit 2; }
  nohup xvfb-run -a env DBEAVER_MCP_ENABLED=true DBEAVER_MCP_PORT="$port" \
    "$product/dbeaver" -data "$workspace" -clean -consoleLog >"$log" 2>&1 < /dev/null &
fi
echo $! >"$pidfile"

for _ in $(seq 1 90); do
  curl -fsS "http://127.0.0.1:$port/healthz" >/tmp/teststudio-runtime-health.json 2>/dev/null && break
  sleep 1
done
if ! curl -fsS "http://127.0.0.1:$port/healthz" >/tmp/teststudio-runtime-health.json; then
  tail -300 "$log" >&2
  exit 1
fi

rpc() {
  local payload=$1 output=$2
  curl -fsS --max-time 60 \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    --data "$payload" "http://127.0.0.1:$port/mcp" >"$output"
}

rpc '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' /tmp/teststudio-runtime-tools.json
rpc '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"dbeaver_teststudio","arguments":{"action":"discover"}}}' /tmp/teststudio-runtime-discover.json
rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"dbeaver_teststudio","arguments":{"action":"capabilities","arguments":{}}}}' /tmp/teststudio-runtime-capabilities.json
rpc '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"dbeaver_workbench","arguments":{"action":"list_commands","arguments":{"search":"teststudio","limit":20}}}}' /tmp/teststudio-runtime-commands.json
rpc '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"dbeaver_teststudio","arguments":{"action":"validate_plan","arguments":{"plan":{"schema_version":"1.0","id":"runtime-smoke","name":"Runtime smoke","targets":{"default":{"connection":"placeholder"}},"steps":[{"id":"health","type":"query","sql":"SELECT 1"}]}}}}}' /tmp/teststudio-runtime-validation.json

python3 - <<'PY'
import json
health=json.load(open('/tmp/teststudio-runtime-health.json'))
tools=json.load(open('/tmp/teststudio-runtime-tools.json'))['result']['tools']
discover=json.load(open('/tmp/teststudio-runtime-discover.json'))['result']
cap=json.load(open('/tmp/teststudio-runtime-capabilities.json'))['result']
commands=json.load(open('/tmp/teststudio-runtime-commands.json'))['result']
validation=json.load(open('/tmp/teststudio-runtime-validation.json'))['result']
assert health['status']=='ok',health
names={item['name'] for item in tools}
assert len(tools)==62,(len(tools),sorted(names))
assert 'dbeaver_teststudio' in names
for response in (discover,cap,commands,validation): assert not response.get('isError',False),response
d=discover['structuredContent']; c=cap['structuredContent']; cmd=commands['structuredContent']; v=validation['structuredContent']
assert d['count']>=44,d
assert c['version']=='2.0.1',c
assert c['extensions']['bridge']=='dbeaver-26',c
assert c['assertions']['count']>=27,c
assert c['reports']['count']>=4,c
assert any(item['id']=='org.jkiss.dbeaver.teststudio.open' for item in cmd['commands']),cmd
assert v['valid'] is True,v
print({'tools':len(tools),'studio_actions':d['count'],'bridge':c['extensions']['bridge'],'assertions':c['assertions']['count'],'reports':c['reports']['count'],'ui_command':True,'plan_validation':v['valid']})
PY

if grep -Eq 'FrameworkEvent ERROR|BundleException|NoClassDefFoundError|NoSuchMethodError' "$log"; then
  echo "Runtime linkage failure detected" >&2
  grep -E 'FrameworkEvent ERROR|BundleException|NoClassDefFoundError|NoSuchMethodError' "$log" >&2
  exit 1
fi
printf 'DBeaver runtime smoke passed on port %s\n' "$port"
