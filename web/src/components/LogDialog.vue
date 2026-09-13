<template>
  <el-dialog v-model="visible" :title="'执行日志 - ' + (log.taskName || '')" width="70%" top="6vh" @closed="stopPoll">
    <div class="progress-bar">
      <el-tag :type="stateTagType">{{ log.state || '-' }}</el-tag>
      <span class="metric">读出: {{ fmtCount(progress.readRecords) }}</span>
      <span class="metric">写入: {{ fmtCount(progress.writeRecords) }}</span>
      <span class="metric">失败: {{ fmtCount(progress.errorRecords) }}</span>
      <span class="metric">流量: {{ fmtBytes(progress.readBytes) }}</span>
      <span class="metric" v-if="progress.recordSpeed">{{ progress.recordSpeed }} rec/s</span>
      <span class="metric" v-if="progress.byteSpeed">{{ progress.byteSpeed }} /s</span>
      <span class="metric">耗时: {{ fmtDuration(progress.durationMs) }}</span>
    </div>
    <div class="log-box" ref="logBox">
      <div v-for="(line, i) in lines" :key="start + i" class="log-line">{{ line }}</div>
      <div v-if="!lines.length" class="log-line" style="color: #999">暂无日志</div>
    </div>
    <div class="log-footer">
      <span>共 {{ total }} 行，当前显示 {{ start + 1 }} - {{ start + lines.length }}</span>
      <el-button size="small" @click="goFirst" :disabled="start <= 0">首页</el-button>
      <el-button size="small" @click="pageUp" :disabled="start <= 0">上一页</el-button>
      <el-button size="small" @click="pageDown" :disabled="start + lines.length >= total">下一页</el-button>
      <el-button size="small" @click="goLast" :disabled="start + lines.length >= total">尾页</el-button>
      <el-button size="small" type="danger" v-if="running" @click="stopJob">停止任务</el-button>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, watch, nextTick, computed } from 'vue'
import http from '../api'
import { ElMessage } from 'element-plus'

const props = defineProps({ modelValue: Boolean, logId: String })
const emit = defineEmits(['update:modelValue'])

const visible = ref(false)
const lines = ref([])
const total = ref(0)
const start = ref(0)
const running = ref(false)
const logBox = ref(null)
const log = reactive({})
const progress = reactive({})
let timer = null
// 运行中自动跟随日志末尾；用户往上翻页查看历史时暂停跟随
const follow = ref(true)
// 上一轮轮询的运行态，用于检测「运行 -> 结束」边沿
const wasRunning = ref(false)
const PAGE = 300

watch(() => props.modelValue, v => {
  visible.value = v
  if (v && props.logId) {
    start.value = 0
    follow.value = true
    wasRunning.value = false
    refresh(true)
    timer = setInterval(() => refresh(false), 1500)
  } else {
    stopPoll()
  }
})
watch(visible, v => emit('update:modelValue', v))

function stopPoll() {
  if (timer) clearInterval(timer)
  timer = null
}

async function refresh(reset) {
  try {
    if (reset) {
      const detail = await http.get(`/task-logs/${props.logId}`)
      Object.assign(log, detail)
    }
    const p = await http.get(`/task-logs/${props.logId}/progress`)
    Object.assign(progress, p)
    // 轮询同步状态标签：progress 的 state 是实时的（log.state 仅打开时快照）
    if (p.state && p.state !== log.state) log.state = p.state
    // QUEUED（已提交、在池队列里等待）也属于"未结束"，需保留停止按钮并允许停止
    running.value = p.state === 'RUNNING' || p.state === 'QUEUED'
    // 「运行 -> 结束」边沿：任务结束时自动补一次尾页定位，展示最终日志
    const justEnded = wasRunning.value && !running.value
    wasRunning.value = running.value
    // 跟随模式下定位到当前末页，运行中持续显示最新日志
    if (running.value && follow.value && total.value > PAGE) {
      start.value = Math.max(0, total.value - PAGE)
    }
    const content = await http.get(`/task-logs/${props.logId}/content`, { params: { start: start.value, max: PAGE } })
    lines.value = content.lines || []
    total.value = content.total
    if (start.value + lines.value.length >= total.value) follow.value = true
    // 任务结束瞬间：用最新 total 重新定位末页，确保最终日志（含结果行）可见
    if (justEnded && follow.value && total.value > start.value + PAGE) {
      start.value = Math.max(0, total.value - PAGE)
      const tail = await http.get(`/task-logs/${props.logId}/content`, { params: { start: start.value, max: PAGE } })
      lines.value = tail.lines || []
      total.value = tail.total
    }
    // 运行中及刚结束时自动滚动到底部
    if ((running.value || justEnded) && logBox.value) {
      nextTick(() => { logBox.value.scrollTop = logBox.value.scrollHeight })
    }
  } catch (e) { /* 拦截器已提示 */ }
}

function pageUp() {
  follow.value = false
  start.value = Math.max(0, start.value - PAGE)
  refresh(false)
}

function pageDown() {
  start.value = start.value + PAGE
  refresh(false)
}

/** 跳到日志开头（滚动条置顶） */
async function goFirst() {
  follow.value = false
  start.value = 0
  await refresh(false)
  nextTick(() => { if (logBox.value) logBox.value.scrollTop = 0 })
}

/** 跳到日志末尾（滚动条到底，恢复自动跟随最新日志） */
async function goLast() {
  follow.value = true
  start.value = Math.max(0, total.value - PAGE)
  await refresh(false)
  nextTick(() => { if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight })
}

async function stopJob() {
  try {
    await http.post(`/task-logs/${props.logId}/stop`)
    ElMessage.success('已发送停止请求')
  } catch (e) { /* ignore */ }
}

function fmtCount(n) {
  return n === null || n === undefined ? '-' : Number(n).toLocaleString()
}

function fmtBytes(b) {
  if (b === null || b === undefined) return '-'
  let v = Number(b)
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return v.toFixed(2) + units[i]
}

function fmtDuration(ms) {
  if (!ms) return '-'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
  return (h ? h + 'h' : '') + (m ? m + 'm' : '') + sec + 's'
}

// computed 保证响应式：函数引用绑定 :type 会把 Function 传给 el-tag 导致颜色失效
const stateTagType = computed(() => {
  switch (log.state) {
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'danger'
    case 'RUNNING': return 'primary'
    case 'QUEUED': return 'info'
    case 'STOPPED': return 'warning'
    default: return 'info'
  }
})
</script>

<style scoped>
.progress-bar {
  display: flex;
  gap: 14px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.metric { font-size: 13px; color: #606266; }
.log-box {
  height: 46vh;
  overflow: auto;
  background: #0d1b2a;
  color: #cde3f7;
  font-family: Consolas, monospace;
  font-size: 12px;
  padding: 10px;
  border-radius: 4px;
}
.log-line { white-space: pre-wrap; word-break: break-all; line-height: 1.55; }
.log-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
