<template>
  <el-card class="fill-card">
    <div class="toolbar">
      <span class="title">定时任务</span>
      <el-button type="primary" icon="Plus" @click="openCreate">新建定时任务</el-button>
    </div>
    <div class="table-wrap">
      <el-table :data="list" border stripe height="100%">
      <el-table-column prop="name" label="任务名" min-width="140" />
      <el-table-column prop="taskName" label="同步任务" min-width="140" />
      <el-table-column prop="expression" label="cron 表达式" width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.status === '1'" @change="v => toggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="下次执行" width="160">
        <template #default="{ row }">{{ fmtTime(row.nextFireTime) }}</template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="340" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" @click="runOnce(row)">立即执行</el-button>
          <el-button size="small" @click="showLogs(row)">执行记录</el-button>
          <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="确认删除该定时任务？" @confirm="del(row)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑定时任务' : '新建定时任务'" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="任务名" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="同步任务" required>
          <el-select v-model="form.taskId" style="width: 100%">
            <el-option v-for="t in tasks" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="cron 表达式" required>
          <el-input v-model="form.expression" placeholder="6 位 cron，如：0 0/30 * * * ?">
            <template #append>
              <el-button @click="previewCron" :loading="previewing">校验</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item v-if="nextTimes.length" label="接下来执行">
          <div style="font-size: 12px; color: #606266; line-height: 1.8">
            <div v-for="(t, i) in nextTimes" :key="i">{{ fmtTime(t) }}</div>
          </div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" :title="'执行记录 - ' + (current ? current.name : '')" size="55%">
      <el-table :data="logs" border size="small">
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="tagType(row.state)" size="small">{{ row.state }}</el-tag>
          </template>
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
import { ref, reactive, onMounted } from 'vue'
import http from '../api'
import LogDialog from '../components/LogDialog.vue'
import { ElMessage } from 'element-plus'

const list = ref([])
const tasks = ref([])
const dialogVisible = ref(false)
const drawerVisible = ref(false)
const logDialogVisible = ref(false)
const logs = ref([])
const current = ref(null)
const activeLogId = ref('')
const previewing = ref(false)
const nextTimes = ref([])

const form = reactive({ id: '', name: '', taskId: '', expression: '', description: '' })

onMounted(load)

function load() {
  http.get('/schedules').then(d => list.value = d || [])
  http.get('/tasks').then(d => tasks.value = d || [])
}

function openCreate() {
  Object.assign(form, { id: '', name: '', taskId: '', expression: '', description: '' })
  nextTimes.value = []
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, { id: row.id, name: row.name, taskId: row.taskId, expression: row.expression, description: row.description })
  nextTimes.value = []
  dialogVisible.value = true
}

async function previewCron() {
  previewing.value = true
  try {
    nextTimes.value = await http.get('/schedules/next-times', { params: { expression: form.expression, count: 3 } }) || []
    if (!nextTimes.value.length) ElMessage.warning('无法计算执行时间，请检查表达式')
  } finally {
    previewing.value = false
  }
}

async function save() {
  const body = { ...form }
  if (form.id) {
    await http.put(`/schedules/${form.id}`, body)
  } else {
    await http.post('/schedules', body)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  load()
}

async function toggle(row, enabled) {
  await http.post(`/schedules/${row.id}/toggle`, null, { params: { enabled } })
  ElMessage.success(enabled ? '已启用' : '已停用')
  load()
}

async function runOnce(row) {
  const r = await http.post(`/schedules/${row.id}/run`)
  ElMessage.success('已触发执行')
  activeLogId.value = r.logId
  logDialogVisible.value = true
}

async function showLogs(row) {
  current.value = row
  logs.value = await http.get(`/schedules/${row.id}/logs`, { params: { limit: 20 } }) || []
  drawerVisible.value = true
}

function viewLog(row) {
  activeLogId.value = row.id
  logDialogVisible.value = true
}

async function del(row) {
  await http.delete(`/schedules/${row.id}`)
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
