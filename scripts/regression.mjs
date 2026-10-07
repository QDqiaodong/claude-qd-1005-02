#!/usr/bin/env node
// =====================================================================
// 综合管廊 · 设施档案一致性回归验证（零依赖，Node ≥ 18，直接用全局 fetch）
//
// 覆盖本次修复的缺陷与必须不被绕过的既有规则：
//   1. 设施编辑（名称 / 位置 / 所属舱段）落库后——更新响应、详情、全量列表、
//      按新舱段筛选、按旧舱段筛选、舱段子项下钻，六处读到的是同一条已保存记录；
//   2. 重复点击保存（顺序 + 并发重复提交同一更新）不产生重复设施、不把旧值写回；
//   3. 编辑不触碰 status（状态只能经报故障 / 恢复流转，编辑不会把旧状态写回）；
//   4. 更新校验：空名称 / 非法类型 / 不存在舱段 / 不存在设备 → 400 中文业务异常；
//   5. 风险事件封控：存在未解除事件的舱段不能恢复运营（SEG-03 / SEG-04）；
//   6. 入廊管线校核：防爆隔离 / 封控停用 / 规格兼容 规则链仍然拦截。
//
// 用法：
//   ./start.sh                 # 先起整栈
//   node scripts/regression.mjs
// 环境：默认打 http://127.0.0.1:8405（取根目录 .env 的 BACKEND_HOST/BACKEND_PORT）。
// 注意：脚本会在库里新建 1 台回归设备（无删除接口），重跑前可 ./clean.sh && ./start.sh 重置。
// =====================================================================

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

// ---------- 连接配置：.env 优先，缺省 127.0.0.1:8405 ----------
function loadBase() {
  let host = '127.0.0.1'
  let port = '8405'
  try {
    const env = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '.env'), 'utf8')
    for (const line of env.split('\n')) {
      const m = line.match(/^\s*(BACKEND_HOST|BACKEND_PORT)\s*=\s*(\S+)\s*$/)
      if (!m) continue
      if (m[1] === 'BACKEND_HOST') host = m[2]
      if (m[1] === 'BACKEND_PORT') port = m[2]
    }
  } catch { /* 无 .env 时用默认值 */ }
  return `http://${host}:${port}/api`
}
const BASE = loadBase()

// ---------- 小框架 ----------
let passed = 0
let failed = 0
function ok(name, cond, extra = '') {
  if (cond) { passed++; console.log(`  ✅ ${name}`) }
  else { failed++; console.log(`  ❌ ${name}${extra ? ' —— ' + extra : ''}`) }
}
function section(title) { console.log(`\n【${title}】`) }

async function req(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined
  })
  let json = null
  try { json = await res.json() } catch { /* 非 JSON 响应 */ }
  return { status: res.status, ok: json?.ok === true, message: json?.message || '', data: json?.data }
}
const get = (p) => req('GET', p)
const post = (p, b) => req('POST', p, b)
const put = (p, b) => req('PUT', p, b)

/** 业务异常必须 400 且带中文提示 */
function expectReject(name, r, keyword) {
  ok(name, r.status === 400 && r.ok === false && (!keyword || r.message.includes(keyword)),
    `status=${r.status} message=${r.message}`)
}

/** 名称 / 位置 / 所属舱段三项必须等于同一组已保存值 */
function sameArchive(f, expect) {
  return f && f.name === expect.name && f.location === expect.location && f.segmentId === expect.segmentId
}

// ---------- 回归主体 ----------
async function main() {
  console.log(`回归目标：${BASE}`)

  // 健康检查
  const health = await get('/dashboard/health')
  if (!health.ok) {
    console.error('后端不可达，请先 ./start.sh 起栈'); process.exit(2)
  }

  const segments = (await get('/segments')).data
  const seg1 = segments.find(s => s.segCode === 'SEG-01')
  const seg2 = segments.find(s => s.segCode === 'SEG-02')
  const seg3 = segments.find(s => s.segCode === 'SEG-03')
  const seg4 = segments.find(s => s.segCode === 'SEG-04')
  if (!seg1 || !seg2 || !seg3 || !seg4) {
    console.error('种子舱段缺失（SEG-01..04），请 ./clean.sh && ./start.sh 重置后重跑'); process.exit(2)
  }

  const stamp = Date.now() % 100000
  const nameV1 = `回归风机A-${stamp}`
  const nameV2 = `回归风机B-${stamp}`
  const locV1 = `桩号 K9+001`
  const locV2 = `桩号 K9+002`

  // ============ 1. 建档 → 详情 / 列表一致 ============
  section('1. 建档后详情与列表一致')
  const created = await post('/facilities', {
    segmentId: seg1.id, name: nameV1, facilityType: '风机', location: locV1, status: '正常'
  })
  ok('建档成功', created.ok && created.data?.id > 0, created.message)
  const fid = created.data.id
  const v1 = { name: nameV1, location: locV1, segmentId: seg1.id }

  const detail1 = (await get(`/facilities/${fid}`)).data
  ok('详情回显 = 建档值', sameArchive(detail1, v1), JSON.stringify(detail1))
  const listAll1 = (await get('/facilities')).data
  ok('全量列表 = 建档值', sameArchive(listAll1.find(f => f.id === fid), v1))
  const listSeg1 = (await get(`/facilities?segmentId=${seg1.id}`)).data
  ok('按 SEG-01 筛选可见', sameArchive(listSeg1.find(f => f.id === fid), v1))

  // ============ 2. 编辑名称+位置+所属舱段 → 六处一致 ============
  section('2. 编辑后：响应 / 详情 / 列表 / 新旧舱段筛选 / 舱段子项 六处一致')
  const v2 = { name: nameV2, location: locV2, segmentId: seg2.id }
  const updated = await put(`/facilities/${fid}`, {
    segmentId: seg2.id, name: nameV2, facilityType: '照明', location: locV2
  })
  ok('更新成功且响应即新记录', updated.ok && sameArchive(updated.data, v2)
    && updated.data.facilityType === '照明', updated.message)

  const detail2 = (await get(`/facilities/${fid}`)).data
  ok('详情 = 新值', sameArchive(detail2, v2) && detail2.facilityType === '照明', JSON.stringify(detail2))
  const rowAll = (await get('/facilities')).data.find(f => f.id === fid)
  ok('全量列表 = 新值', sameArchive(rowAll, v2))
  const rowNew = (await get(`/facilities?segmentId=${seg2.id}`)).data.find(f => f.id === fid)
  ok('按新舱段 SEG-02 筛选可见且为新值', sameArchive(rowNew, v2))
  const oldSegList = (await get(`/facilities?segmentId=${seg1.id}`)).data
  ok('按旧舱段 SEG-01 筛选不再出现', !oldSegList.some(f => f.id === fid))
  const children = (await get(`/segments/${seg2.id}/with-children`)).data
  ok('舱段子项下钻 = 新值', sameArchive((children.facilities || []).find(f => f.id === fid), v2))
  ok('六处为同一条记录（id 不变）', detail2.id === fid && rowAll.id === fid && rowNew.id === fid)

  // ============ 3. 重复保存：不产生重复设施、不写回旧值 ============
  section('3. 重复点击保存（顺序 ×2 + 并发 ×3）幂等')
  const countBefore = (await get('/facilities')).data.length
  const seq1 = await put(`/facilities/${fid}`, { segmentId: seg2.id, name: nameV2, facilityType: '照明', location: locV2 })
  const seq2 = await put(`/facilities/${fid}`, { segmentId: seg2.id, name: nameV2, facilityType: '照明', location: locV2 })
  ok('顺序重复提交均成功', seq1.ok && seq2.ok)
  const dup = { segmentId: seg2.id, name: nameV2, facilityType: '照明', location: locV2 }
  const concurrent = await Promise.all([put(`/facilities/${fid}`, dup), put(`/facilities/${fid}`, dup), put(`/facilities/${fid}`, dup)])
  ok('并发重复提交均成功', concurrent.every(r => r.ok))
  const afterDup = (await get('/facilities')).data
  ok('未生成重复设施（总数不变）', afterDup.length === countBefore, `before=${countBefore} after=${afterDup.length}`)
  ok('同名设备仍只有 1 台', afterDup.filter(f => f.name === nameV2).length === 1)
  ok('值未被写回旧值', sameArchive(afterDup.find(f => f.id === fid), v2))
  ok('详情仍为新值', sameArchive((await get(`/facilities/${fid}`)).data, v2))

  // ============ 4. 编辑不触碰 status（状态机不被绕过） ============
  section('4. 编辑不写回旧状态（status 只能经报故障 / 恢复流转）')
  const faulted = await post(`/facilities/${fid}/fault`)
  ok('报故障成功', faulted.ok && faulted.data.status === '故障')
  const editAfterFault = await put(`/facilities/${fid}`, { segmentId: seg2.id, name: nameV2, facilityType: '照明', location: locV2 })
  ok('编辑后状态仍为「故障」', editAfterFault.ok && editAfterFault.data.status === '故障',
    `status=${editAfterFault.data?.status}`)
  ok('详情状态一致', (await get(`/facilities/${fid}`)).data.status === '故障')
  const restored = await post(`/facilities/${fid}/restore`)
  ok('恢复成功', restored.ok && restored.data.status === '正常')

  // ============ 5. 更新校验 ============
  section('5. 更新入参校验（400 + 中文提示）')
  expectReject('名称为空被拒', await put(`/facilities/${fid}`, { segmentId: seg2.id, name: '  ', facilityType: '照明' }), '名称')
  expectReject('非法设备类型被拒', await put(`/facilities/${fid}`, { segmentId: seg2.id, name: nameV2, facilityType: '锅炉' }), '设备类型')
  expectReject('不存在舱段被拒', await put(`/facilities/${fid}`, { segmentId: 999999, name: nameV2, facilityType: '照明' }), '舱段')
  expectReject('不存在设备被拒', await put('/facilities/999999', { segmentId: seg2.id, name: nameV2, facilityType: '照明' }), '不存在')
  ok('校验失败后记录未被污染', sameArchive((await get(`/facilities/${fid}`)).data, v2))

  // ============ 6. 风险事件封控未被绕过 ============
  section('6. 风险封控：未解除事件的舱段不能恢复运营')
  expectReject('SEG-03（处理中事件）恢复运营被拒',
    await post(`/segments/${seg3.id}/status`, { status: '运营' }), '封控')
  expectReject('SEG-04（待处置事件）恢复运营被拒',
    await post(`/segments/${seg4.id}/status`, { status: '运营' }), '封控')
  ok('SEG-03 仍为「维护」', (await get(`/segments/${seg3.id}`)).data.status === '维护')
  ok('SEG-04 仍为「停用」', (await get(`/segments/${seg4.id}`)).data.status === '停用')

  // ============ 7. 入廊管线校核未被绕过 ============
  section('7. 入廊管线校核：隔离 / 封控 / 规格规则链')
  expectReject('SEG-01 已有电力 → 燃气禁入（防爆隔离）',
    await post('/pipelines', { segmentId: seg1.id, lineType: '燃气', diameterMm: 200, pressureLevel: '中压', status: '在用' }), '防爆隔离')
  expectReject('SEG-04 封控+停用 → 禁止入廊',
    await post('/pipelines', { segmentId: seg4.id, lineType: '电力', diameterMm: 300, pressureLevel: '高压', status: '在用' }))
  expectReject('通信（无压）不得标注压力级制',
    await post('/pipelines', { segmentId: seg2.id, lineType: '通信', diameterMm: 100, pressureLevel: '低压', status: '在用' }), '无压')
  expectReject('管径超上限被拒',
    await post('/pipelines', { segmentId: seg2.id, lineType: '通信', diameterMm: 9999, status: '在用' }), '上限')
  const seg2After = (await get(`/segments/${seg2.id}/with-children`)).data
  ok('被拒管线均未落库（SEG-02 仍只有 1 条排水）',
    (seg2After.pipelines || []).length === 1, JSON.stringify((seg2After.pipelines || []).map(p => p.lineType)))

  // ---------- 汇总 ----------
  console.log(`\n========================================`)
  console.log(`回归结果：${passed} 通过，${failed} 失败`)
  if (failed > 0) process.exit(1)
  console.log('全部通过 ✅')
}

main().catch(e => { console.error('回归脚本异常：', e); process.exit(2) })
