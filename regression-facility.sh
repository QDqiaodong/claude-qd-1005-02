#!/usr/bin/env bash
# 设施档案回归脚本（真实 HTTP + 真实 MySQL）。
# 覆盖题述缺陷：编辑后列表/筛选/详情/舱段下钻必须读到同一条已保存记录；
# 重复保存不生成重复设施、不把旧值写回；风险封控与入廊管线校核不被绕过。
#
# 用法：BASE=http://127.0.0.1:8080 ./regression-facility.sh
set -u
BASE="${BASE:-http://127.0.0.1:8080}"
API="$BASE/api"
PASS=0; FAIL=0
# 每次运行用唯一名，脚本可直接重复执行
STAMP="$(date +%s)"
BASE_NAME="回归脚本风机-$STAMP"
CHANGED_NAME="$BASE_NAME-改"

ok()  { echo "  ✓ $1"; PASS=$((PASS+1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL+1)); }
assert_eq() { # expected actual desc
  if [ "$1" = "$2" ]; then ok "$3"; else bad "$3（期望[$1] 实际[$2]）"; fi
}

# http METHOD PATH [JSON]：全局变量 BODY / CODE（命令替换在子 shell，故用全局变量回传）
http() {
  if [ -n "${3:-}" ]; then
    RESP=$(curl -s -w $'\n%{http_code}' -X "$1" "$API$2" -H 'Content-Type: application/json' -d "$3")
  else
    RESP=$(curl -s -w $'\n%{http_code}' -X "$1" "$API$2")
  fi
  CODE="${RESP##*$'\n'}"
  BODY="${RESP%$'\n'*}"
}
jfield() { # python 表达式（基于 d）从 $BODY 取值
  BODY="$BODY" python3 -c "import os,json;d=json.loads(os.environ['BODY']);print($1)"
}

# 读取设施在五种视角下的「名称|位置|舱段id」；旧舱段视角无该设施时输出 MISSING
fetch_view() { # id view(list|detail|f1|f2|d2)
  python3 - "$API" "$1" "$2" <<'PY'
import sys, json, urllib.request
api, fid, view = sys.argv[1], int(sys.argv[2]), sys.argv[3]
def g(path):
    return json.load(urllib.request.urlopen(api + path))
r = None
if view == 'list':
    r = next(x for x in g('/facilities')['data'] if x['id'] == fid)
elif view == 'detail':
    r = g(f'/facilities/{fid}')['data']
elif view in ('f1', 'f2'):
    seg = view[1]
    r = next((x for x in g(f'/facilities?segmentId={seg}')['data'] if x['id'] == fid), None)
elif view == 'd2':
    d = g('/segments/2/with-children')['data']
    r = next(x for x in d['facilities'] if x['id'] == fid)
print('MISSING' if r is None
      else f"{r['facilityName']}|{r.get('location') or ''}|{r['segmentId']}")
PY
}

echo "1) 建档"
http POST /facilities "{\"segmentId\":1,\"facilityName\":\"$BASE_NAME\",\"facilityType\":\"风机\",\"location\":\"旧位置\",\"status\":\"正常\"}"
assert_eq True "$(jfield "d['ok']")" "建档成功"
assert_eq 200 "$CODE" "建档 HTTP 200"
ID=$(jfield "d['data']['id']")

echo "2) 更新名称 / 位置 / 所属舱段（SEG-01 → SEG-02）"
http PUT "/facilities/$ID" "{\"segmentId\":2,\"facilityName\":\"$CHANGED_NAME\",\"facilityType\":\"风机\",\"location\":\"新位置\",\"status\":\"正常\",\"updatedAt\":null}"
assert_eq True "$(jfield "d['ok']")" "更新成功"
V=$(jfield "d['data'].get('updatedAt') or ''")
[ -n "$V" ] && ok "返回行版本 updatedAt" || bad "缺少 updatedAt"

echo "3) 列表 / 详情 / 新舱段筛选 / 新舱段下钻读到同一条已保存记录"
EXPECT="$CHANGED_NAME|新位置|2"
assert_eq "$EXPECT" "$(fetch_view "$ID" list)"   "全量列表"
assert_eq "$EXPECT" "$(fetch_view "$ID" detail)" "详情回显"
assert_eq MISSING   "$(fetch_view "$ID" f1)"     "旧舱段筛选不可见"
assert_eq "$EXPECT" "$(fetch_view "$ID" f2)"     "新舱段筛选"
assert_eq "$EXPECT" "$(fetch_view "$ID" d2)"     "新舱段详情下钻"

echo "4) 旧版本重复提交：被拒，旧值不写回"
http PUT "/facilities/$ID" "{\"segmentId\":1,\"facilityName\":\"$BASE_NAME\",\"facilityType\":\"风机\",\"location\":\"旧位置\",\"status\":\"正常\",\"updatedAt\":null}"
assert_eq 400 "$CODE" "陈旧版本提交被拒(400)"
assert_eq "$EXPECT" "$(fetch_view "$ID" detail)" "库里仍为新值"

echo "5) 同舱同名重复建档：被拒，仅一条"
http POST /facilities "{\"segmentId\":2,\"facilityName\":\"$CHANGED_NAME\",\"facilityType\":\"照明\",\"location\":\"x\",\"status\":\"正常\"}"
assert_eq 400 "$CODE" "同舱同名重复建档被拒"
CNT=$(NAME="$CHANGED_NAME" python3 - "$API" <<'PY'
import os, sys, json, urllib.request
name = os.environ['NAME']
rows = json.load(urllib.request.urlopen(sys.argv[1] + '/facilities?segmentId=2'))['data']
print(sum(1 for x in rows if x['facilityName'] == name))
PY
)
assert_eq 1 "$CNT" "同舱同名记录只有一条"

echo "6) 报故障重复点击只生效一次"
http POST "/facilities/$ID/fault"
assert_eq 200 "$CODE" "首次报故障 200"
http POST "/facilities/$ID/fault"
assert_eq 400 "$CODE" "重复报故障被拒(400)"

echo "7) 封控：SEG-03 有未解除风险，新管线入廊必须被拦"
http POST /pipelines '{"segmentId":3,"lineType":"燃气","diameterMm":200,"pressureLevel":"中压","status":"在用"}'
assert_eq 400 "$CODE" "封控舱管线入廊被拦"

echo "8) 防爆隔离：SEG-01 已敷设电力，燃气入廊必须被拦"
http POST /pipelines '{"segmentId":1,"lineType":"燃气","diameterMm":200,"pressureLevel":"低压","status":"在用"}'
assert_eq 400 "$CODE" "电力舱燃气被防爆隔离拦截"

echo
echo "结果：通过 $PASS，失败 $FAIL"
[ "$FAIL" = 0 ]
