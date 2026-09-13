<template>
  <el-card class="fill-card">
    <div class="toolbar">
      <span class="title">数据传输</span>
      <el-button type="primary" icon="Plus" @click="$router.push('/tasks/new')">新建传输任务</el-button>
    </div>
    <div class="table-wrap">
      <el-table :data="list" border stripe height="100%">
      <el-table-column prop="name" label="任务名称" min-width="150" />
      <el-table-column label="传输方向" min-width="260">
        <template #default="{ row }">
          {{ dsName(row.sourceDatasourceId) }} / {{ row.sourceDatabase }}
          <el-icon style="vertical-align: middle"><Right /></el-icon>
          {{ dsName(row.targetDatasourceId) }} / {{ row.targetDatabase }}
        </template>
      </el-table-column>
      <el-table-column label="表数量" width="80">
        <template #default="{ row }">{{ tableCount(row) }}</template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="120" show-overflow-tooltip />
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.updateDate || row.createDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="340" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" icon="VideoPlay" @click="run(row)">执行</el-button>
          <el-button size="small" icon="Document" @click="showLogs(row)">执行记录</el-button>
          <el-button size="small" type="primary" @click="$router.push('/tasks/edit/' + row.id)">编辑</el-button>
          <el-popconfirm title="确认删除该任务？" @confirm="del(row)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-drawer v-model="drawerVisible" :title="'执行记录 - ' + (current ? current.name : '')" size="60%">
      <el-table :data="logs" border size="small">
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="tagType(row.state)" size="small">{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="触发方式" width="90">
          <template #default="{ row }">{{ row.triggerType === 'schedule' ? '定时' : '手动' }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="读出/写入" width="140">
          <template #default="{ row }">{{ fmtCount(row.readRecords) }} / {{ fmtCount(row.writeRecords) }}</template>
        </el-table-column>
        <el-table-column prop="message" label="信息" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="viewLog(row)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <LogDialog v-model="logDialogVisible" :log-id="activeLogId" />
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '../api'
import LogDialog from '../components/LogDialog.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const list = ref([])
const sources = ref([])
const drawerVisible = ref(false)
const logDialogVisible = ref(false)
const logs = ref([])
const current = ref(null)
const activeLogId = ref('')

onMounted(() => {
  load()
  http.get('/datasources').then(d => sources.value = d || [])
})

function load() {
  http.get('/tasks').then(d => list.value = d || [])
}

function dsName(id) {
  const ds = sources.value.find(s => s.id === id)
  return ds ? ds.name : id
}

/** 源/目标端点描述：数据源名称 + IP:端口/库名 */
function dsEndpoint(id, database) {
  const ds = sources.value.find(s => s.id === id)
  return ds ? `${ds.name} ${ds.host}:${ds.port}/${database || '-'}` : `${dsName(id)}/${database || '-'}`
}

function tableCount(row) {
  try {
    const cfg = JSON.parse(row.config || '{}')
    return (cfg.tables || []).filter(t => t.enabled).length
  } catch (e) { return '-' }
}

async function run(row) {
  try {
    await ElMessageBox.confirm(
      `任务「${row.name}」将开始同步数据，并按执行计划写入目标库（${dsEndpoint(row.sourceDatasourceId, row.sourceDatabase)} -> ${dsEndpoint(row.targetDatasourceId, row.targetDatabase)}），是否继续？`,
      '执行确认',
      { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  const r = await http.post(`/tasks/${row.id}/run`)
  ElMessage.success('任务已开始执行')
  activeLogId.value = r.logId
  logDialogVisible.value = true
}

async function showLogs(row) {
  current.value = row
  logs.value = await http.get(`/tasks/${row.id}/logs`, { params: { limit: 50 } }) || []
  drawerVisible.value = true
}

function viewLog(row) {
  activeLogId.value = row.id
  logDialogVisible.value = true
}

async function del(row) {
  await http.delete(`/tasks/${row.id}`)
  ElMessage.success('删除成功')
  load()
}

function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function fmtCount(n) {
  return n === null || n === undefined ? '-' : Number(n).toLocaleString()
}

function fmtDuration(ms) {
  if (!ms) return '-'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60)
  return (h ? h + 'h' : '') + (m ? m + 'm' : '') + (s % 60) + 's'
}

function tagType(state) {
  switch (state) {
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'danger'
    case 'RUNNING': return 'primary'
    case 'QUEUED': return 'info'
    case 'STOPPED': return 'warning'
    default: return 'info'
  }
}
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.title { font-size: 16px; font-weight: 600; }
</style>
