<template>
  <el-card class="fill-card">
    <div class="toolbar">
      <span class="title">执行日志</span>
      <div style="display: flex; gap: 10px">
        <el-select v-model="query.state" placeholder="状态" clearable style="width: 130px" @change="load(1)">
          <el-option label="RUNNING" value="RUNNING" />
          <el-option label="QUEUED" value="QUEUED" />
          <el-option label="SUCCESS" value="SUCCESS" />
          <el-option label="FAILED" value="FAILED" />
          <el-option label="STOPPED" value="STOPPED" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="任务名称" clearable style="width: 200px" @keyup.enter="load(1)" />
        <el-button type="primary" icon="Search" @click="load(1)">查询</el-button>
      </div>
    </div>
    <div class="table-wrap">
      <el-table :data="list" border stripe height="100%">
      <el-table-column prop="taskName" label="任务名称" min-width="140" />
      <el-table-column label="状态" width="100">
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
      <el-table-column label="读出" width="100">
        <template #default="{ row }">{{ fmtCount(row.readRecords) }}</template>
      </el-table-column>
      <el-table-column label="写入" width="100">
        <template #default="{ row }">{{ fmtCount(row.writeRecords) }}</template>
      </el-table-column>
      <el-table-column label="速度" width="150">
        <template #default="{ row }">{{ row.speedRecord || '-' }} / {{ row.speedByte || '-' }}</template>
      </el-table-column>
      <el-table-column prop="message" label="信息" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="viewLog(row)">日志</el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>
    <el-pagination style="margin-top: 14px; justify-content: flex-end" background layout="total, prev, pager, next, sizes"
                   :total="total" v-model:current-page="query.page" v-model:page-size="query.size"
                   :page-sizes="[20, 50, 100]" @current-change="load()" @size-change="load(1)" />
    <LogDialog v-model="logDialogVisible" :log-id="activeLogId" />
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import http from '../api'
import LogDialog from '../components/LogDialog.vue'

const list = ref([])
const total = ref(0)
const query = reactive({ state: '', keyword: '', page: 1, size: 20 })
const logDialogVisible = ref(false)
const activeLogId = ref('')

onMounted(() => load())

function load(page) {
  if (page) query.page = page
  http.get('/task-logs', { params: query }).then(d => {
    list.value = d.list || []
    total.value = d.total || 0
  })
}

function viewLog(row) {
  activeLogId.value = row.id
  logDialogVisible.value = true
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
