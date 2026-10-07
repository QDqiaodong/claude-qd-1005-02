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
function segCodeOf(row) {
  // 舱段编码以后端 JOIN 已保存记录带出的值为准，本地映射仅兜底
  return row.segCode || segMap.value[row.segmentId] || row.segmentId
}
const error = ref('')
const tip = ref('')
const loading = ref(false)
const segmentFilter = ref('')

const facilityTypes = ['风机', '排水泵', '照明', '监控']
const statuses = ['正常', '故障']

const dialog = ref(false)
const dialogTitle = ref('新建设施')
const editingId = ref(null)
const saving = ref(false)
const form = ref(blank())
function blank() {
  return {
    id: null,
    segmentId: '',
    facilityName: '',
    facilityType: '风机',
    location: '',
    status: '正常',
    updatedAt: null
  }
}

// 详情下钻：每次打开都重新请求，回显与列表/刷新来自同一条已保存记录
const detail = ref(null)
const detailOpen = ref(false)
const detailLoading = ref(false)

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
  form.value = blank()
  if (segments.value.length) form.value.segmentId = segments.value[0].id
  editingId.value = null
  dialogTitle.value = '新建设施'
  dialog.value = true; tip.value = ''; error.value = ''
}

// 编辑：先按 id 拉取已保存记录再回填，杜绝用列表里残留的旧值回显
async function openEdit(row) {
  tip.value = ''; error.value = ''
  try {
    const f = await api.getFacility(row.id)
    form.value = {
      id: f.id,
      segmentId: f.segmentId,
      facilityName: f.facilityName || '',
      facilityType: f.facilityType,
      location: f.location || '',
      status: f.status,
      // 行版本随表单带回；陈旧表单提交会被后端拒绝
      updatedAt: f.updatedAt
    }
    editingId.value = f.id
    dialogTitle.value = `编辑设施 #${f.id}`
    dialog.value = true
  } catch (e) { error.value = e.message }
}

async function openDetail(row) {
  detail.value = null
  detailOpen.value = true
  detailLoading.value = true
  error.value = ''
  try {
    detail.value = await api.getFacility(row.id)
  } catch (e) {
    error.value = e.message
    detailOpen.value = false
  } finally { detailLoading.value = false }
}

async function save() {
  // 保存进行中直接忽略后续点击，避免重复提交生成重复设施 / 重复更新
  if (saving.value) return
  if (!form.value.facilityName || !form.value.facilityName.trim()) {
    error.value = '设施名称不能为空'
    return
  }
  if (!form.value.segmentId) {
    error.value = '必须选择所属舱段'
    return
  }
  saving.value = true; error.value = ''
  try {
    const payload = {
      segmentId: form.value.segmentId,
      facilityName: form.value.facilityName.trim(),
      facilityType: form.value.facilityType,
      location: form.value.location && form.value.location.trim()
        ? form.value.location.trim() : null,
      status: form.value.status
    }
    if (editingId.value) {
      payload.updatedAt = form.value.updatedAt
      const saved = await api.updateFacility(editingId.value, payload)
      // 用后端回库重读的版本刷新表单版本，关闭后重新拉列表
      form.value.updatedAt = saved.updatedAt
      tip.value = `设施「${saved.facilityName}」档案已保存`
    } else {
      await api.createFacility(payload)
      tip.value = `设施（${payload.facilityName}）已建档`
    }
    dialog.value = false
    await Promise.all([load(), refreshHealth()])
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function fault(row) {
  error.value = ''; tip.value = ''
  try {
    await api.faultFacility(row.id)
    tip.value = `${row.facilityName} 已报故障`
    await Promise.all([load(), refreshHealth()])
  } catch (e) { error.value = e.message }
}
async function restore(row) {
  error.value = ''; tip.value = ''
  try {
    await api.restoreFacility(row.id)
    tip.value = `${row.facilityName} 已恢复`
    await Promise.all([load(), refreshHealth()])
  } catch (e) { error.value = e.message }
}

function openRisk(row) {
  riskPreset.value = {
    segmentId: row.segmentId,
    sourceType: '附属设备',
    sourceId: row.id,
    sourceLabel: `${row.facilityName}（${row.facilityType}·${row.status}）#${row.id}`
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
        <span class="panel-title">附属设施</span>
        <span class="panel-desc">名称、位置、所属舱段保存后列表 / 筛选 / 详情回显一致；设备报故障后置「故障」，修复后恢复「正常」</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">设施数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">正常</div><div class="v">{{ rows.filter(r => r.status === '正常').length }}</div></div>
        <div class="stat"><div class="k">故障</div><div class="v">{{ rows.filter(r => r.status === '故障').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="segmentFilter" placeholder="全部舱段" clearable style="width: 160px" @change="load">
          <el-option v-for="s in segments" :key="s.id" :label="s.segCode" :value="s.id" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建设施</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="facilityName" label="设施名称" min-width="180" />
        <el-table-column label="所属舱段" width="130">
          <template #default="{ row }">{{ segCodeOf(row) }}</template>
        </el-table-column>
        <el-table-column prop="facilityType" label="设备类型" width="100" />
        <el-table-column prop="location" label="安装位置" min-width="170">
          <template #default="{ row }">{{ row.location || '—' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '正常' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="360" fixed="right">
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

    <el-dialog v-model="dialog" :title="dialogTitle" width="560px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="设施名称">
          <el-input v-model="form.facilityName" maxlength="64" placeholder="如：北段 1# 送风机组" />
        </el-form-item>
        <el-form-item label="所属舱段">
          <el-select v-model="form.segmentId" style="width: 100%" placeholder="选择舱段">
            <el-option v-for="s in segments" :key="s.id" :label="`${s.segCode}（${s.segName}）`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="form.facilityType" style="width: 100%" :disabled="editingId !== null">
            <el-option v-for="t in facilityTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="安装位置">
          <el-input v-model="form.location" maxlength="128" placeholder="如：桩号 K0+050 舱顶" />
        </el-form-item>
        <el-form-item v-if="editingId === null" label="状态" class="full">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ editingId === null ? '建档' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailOpen" title="设施详情" width="560px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="1" border>
          <el-descriptions-item label="设施名称">{{ detail.facilityName }}</el-descriptions-item>
          <el-descriptions-item label="所属舱段">{{ detail.segCode }}（{{ detail.segName }}）</el-descriptions-item>
          <el-descriptions-item label="设备类型">{{ detail.facilityType }}</el-descriptions-item>
          <el-descriptions-item label="安装位置">{{ detail.location || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.status === '正常' ? 'success' : 'danger'">{{ detail.status }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
      </template>
    </el-dialog>

    <risk-register-dialog v-model="riskOpen" :preset="riskPreset" @saved="onRiskSaved" />
  </div>
</template>
