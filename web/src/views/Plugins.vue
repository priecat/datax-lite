<template>
  <el-card class="fill-scroll">
    <div class="toolbar">
      <span class="title">插件管理</span>
      <el-button icon="Refresh" @click="load">刷新</el-button>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom: 14px"
              title="插件目录：应用根目录 plugin/reader 与 plugin/writer。将插件（含 plugin.json、插件 jar、libs）放入对应目录后重启应用生效。" />
    <div v-for="type in ['reader', 'writer']" :key="type">
      <el-divider content-position="left">{{ type === 'reader' ? 'Reader（读取）' : 'Writer（写入）' }}</el-divider>
      <el-row :gutter="14">
        <el-col :span="8" v-for="p in groupOf(type)" :key="p.path">
          <el-card shadow="hover" style="margin-bottom: 14px">
            <div class="p-name">
              <el-tag :type="type === 'reader' ? 'success' : 'warning'" size="small">{{ type }}</el-tag>
              <b>{{ p.name }}</b>
              <span class="p-version">{{ p.version }}</span>
            </div>
            <div class="p-desc">{{ p.description || '无描述' }}</div>
            <div class="p-meta">开发者：{{ p.developer || '-' }}</div>
            <div class="p-meta">文件：{{ (p.jars || []).length }} 个 jar</div>
            <div class="p-meta path" :title="p.path">{{ p.path }}</div>
          </el-card>
        </el-col>
        <el-col :span="8" v-if="!groupOf(type).length">
          <el-empty description="暂无插件" :image-size="60" />
        </el-col>
      </el-row>
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '../api'

const list = ref([])
onMounted(load)

function load() {
  http.get('/plugins').then(d => list.value = d || [])
}

function groupOf(type) {
  return list.value.filter(p => p.type === type)
}
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.title { font-size: 16px; font-weight: 600; }
.p-name { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.p-version { color: #909399; font-size: 12px; }
.p-desc { font-size: 13px; color: #606266; margin-bottom: 6px; min-height: 18px; }
.p-meta { font-size: 12px; color: #909399; line-height: 1.7; }
.p-meta.path { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
