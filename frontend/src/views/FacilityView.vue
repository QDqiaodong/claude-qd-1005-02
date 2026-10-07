<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import api from '../api'
import RiskRegisterDialog from '../components/RiskRegisterDialog.vue'

const refreshHealth = inject('refreshHealth', () => {})

const rows = ref([])
const segments = ref([])
const segMap = computed(() => {
  const m = {}
  segments.value.forEach(s => { m[s.id] = s.segCode })
  return m
})
const error = ref('')
const tip = ref('')
const loading = ref(false)
const segmentFilter = ref('')

const facilityTypes = ['风机', '排水泵', '照明', '监控']
const statuses = ['正常', '故障']

// 建档 / 编辑共用一个对话框：editingId 为 null 是建档，否则是编辑该 id
const dialog = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = ref(blank())
function blank() {
  return { segmentId: '', name: '', facilityType: '风机', location: '', status: '正常' }
}

// 详情下钻：每次打开都重新查服务端已保存记录，不用列表里的旧快照
const detail = ref(null)
const detailOpen = ref(false)

// 从设备登记风险：预填并锁定发现对象
const riskOpen = ref(false)
const riskPreset = ref(null)

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listFacilities(segmentFilter.value || '')
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() {
  editingId.value = null
  form.value = blank()
  if (segments.value.length) form.value.segmentId = segments.value[0].id
  dialog.value = true; tip.value = ''; error.value = ''
}

// 编辑：先拉详情（已保存记录）再回显表单，避免用列表里的旧值覆盖并发修改
async function openEdit(row) {
  tip.value = ''; error.value = ''
  try {
    const f = await api.getFacility(row.id)
    editingId.value = f.id
    form.value = {
      segmentId: f.segmentId,
      name: f.name || '',
      facilityType: f.facilityType,
      location: f.location || '',
      status: f.status
    }
    dialog.value = true
  } catch (e) { error.value = e.message }
}

async function save() {
  // 防重复点击：请求在途时直接忽略，编辑按 id 更新也不会产生重复设备
  if (saving.value) return
  if (!form.value.segmentId) { error.value = '请选择所属舱段'; return }
  if (!form.value.name || !form.value.name.trim()) { error.value = '请填写设备名称'; return }
  saving.value = true; error.value = ''
  try {
    const d = { ...form.value, name: form.value.name.trim() }
    if (editingId.value == null) {
      await api.createFacility(d)
      tip.value = `设备「${d.name}」已建档`
    } else {
      // 响应即落库后回读的同一条记录；列表随后整体重查，三处回显一致
      const saved = await api.updateFacility(editingId.value, d)
      tip.value = `设备「${saved.name}」档案已保存`
    }
    dialog.value = false
    await Promise.all([load(), refreshHealth()])
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

// 详情下钻：实时查服务端，刷新 / 舱段筛选 / 详情三处一致
async function openDetail(row) {
  error.value = ''
  try {
    detail.value = await api.getFacility(row.id)
    detailOpen.value = true
  } catch (e) { error.value = e.message }
}

async function fault(row) {
  error.value = ''; tip.value = ''
  try {
    await api.faultFacility(row.id)
    tip.value = `${row.name || row.facilityType} 已报故障`
    await Promise.all([load(), refreshHealth()])
  } catch (e) { error.value = e.message }
}
async function restore(row) {
  error.value = ''; tip.value = ''
  try {
    await api.restoreFacility(row.id)
    tip.value = `${row.name || row.facilityType} 已恢复`
    await Promise.all([load(), refreshHealth()])
  } catch (e) { error.value = e.message }
}

function openRisk(row) {
  riskPreset.value = {
    segmentId: row.segmentId,
    sourceType: '附属设备',
    sourceId: row.id,
    sourceLabel: `${row.name || row.facilityType}（${row.facilityType}·${row.status}）#${row.id}`
  }
  riskOpen.value = true
  error.value = ''
}
async function onRiskSaved() {
  tip.value = '风险事件已登记，可在「风险事件」模块处置'
  await refreshHealth()
}

onMounted(async () => {
  try { segments.value = await api.listSegments() } catch (e) {}
  await load()
})
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">附属设备</span>
        <span class="panel-desc">编辑名称 / 位置 / 所属舱段即改即存；设备报故障将状态置为「故障」，修复后恢复「正常」</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">设备数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">正常</div><div class="v">{{ rows.filter(r => r.status === '正常').length }}</div></div>
        <div class="stat"><div class="k">故障</div><div class="v">{{ rows.filter(r => r.status === '故障').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="segmentFilter" placeholder="全部舱段" clearable style="width: 160px" @change="load">
          <el-option v-for="s in segments" :key="s.id" :label="s.segCode" :value="s.id" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建设备</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column label="所属舱段" width="120">
          <template #default="{ row }">{{ segMap[row.segmentId] || row.segmentId }}</template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="facilityType" label="设备类型" width="100" />
        <el-table-column prop="location" label="位置" min-width="160">
          <template #default="{ row }">{{ row.location || '—' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '正常' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button size="small" type="primary" plain @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" plain :disabled="row.status === '故障'" @click="fault(row)">报故障</el-button>
            <el-button size="small" :disabled="row.status === '正常'" @click="restore(row)">恢复</el-button>
            <el-button size="small" type="danger" plain @click="openRisk(row)">登记风险</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="editingId == null ? '新建附属设备' : `编辑设备 #${editingId}`" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="所属舱段">
          <el-select v-model="form.segmentId" style="width: 100%" placeholder="选择舱段">
            <el-option v-for="s in segments" :key="s.id" :label="s.segCode" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备名称"><el-input v-model="form.name" placeholder="如 1#轴流风机" /></el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="form.facilityType" style="width: 100%">
            <el-option v-for="t in facilityTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="位置" class="full"><el-input v-model="form.location" placeholder="如 桩号 K0+100 风亭" /></el-form-item>
        <el-form-item v-if="editingId == null" label="状态" class="full">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-else label="状态" class="full">
          <el-tag :type="form.status === '正常' ? 'success' : 'danger'">{{ form.status }}</el-tag>
          <span class="muted" style="margin-left: 8px">状态经「报故障 / 恢复」流转，编辑不改状态</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="save">{{ editingId == null ? '建档' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailOpen" title="设备详情" width="520px">
      <div v-if="detail" class="dialog-form">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="设备编号">#{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="所属舱段">{{ segMap[detail.segmentId] || detail.segmentId }}</el-descriptions-item>
          <el-descriptions-item label="设备类型">{{ detail.facilityType }}</el-descriptions-item>
          <el-descriptions-item label="位置">{{ detail.location || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.status === '正常' ? 'success' : 'danger'">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="建档时间">{{ detail.createdAt || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <risk-register-dialog v-model="riskOpen" :preset="riskPreset" @saved="onRiskSaved" />
  </div>
</template>
