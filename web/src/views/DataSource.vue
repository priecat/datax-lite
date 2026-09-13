<template>
  <el-card class="fill-card">
    <div class="toolbar">
      <span class="title">数据源管理</span>
      <el-button type="primary" icon="Plus" @click="openCreate">新建数据源</el-button>
    </div>
    <div class="table-wrap">
      <el-table :data="list" border stripe height="100%">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="类型" width="130">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column label="地址" min-width="180">
        <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户名" width="120" />
      <el-table-column prop="extraParams" label="额外参数" min-width="140" show-overflow-tooltip />
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="testSaved(row)">测试</el-button>
          <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="确认删除该数据源？" @confirm="del(row)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据源' : '新建数据源'" width="560px">
      <!-- 字段全部由后端 GET /meta/db-types 下发的 schema 驱动渲染：
           新增数据库类型不需要改本文件，也不会出现 v-if 分支链 -->
      <el-form :model="form" label-width="110px">
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%" @change="onTypeChange">
            <el-option v-for="t in dbTypes" :key="t.type" :label="t.displayName" :value="t.type" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：测试库" />
        </el-form-item>
        <!-- v-model 不能绑定函数调用，改用 model-value + update 回调（Vue 编译器约束） -->
        <el-form-item v-for="f in fields" :key="f.key" :label="f.label" :required="f.required">
          <el-select v-if="f.type === 'select'" :model-value="fieldValue(f)" style="width: 100%" @update:model-value="v => setFieldValue(f, v)">
            <el-option v-for="o in (f.options || [])" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input-number
            v-else-if="f.type === 'number'"
            :model-value="fieldValue(f)"
            :min="f.min != null ? f.min : 1"
            :max="f.max != null ? f.max : 65535"
            @update:model-value="v => setFieldValue(f, v)"
          />
          <el-input
            v-else-if="f.type === 'password'"
            :model-value="fieldValue(f)"
            type="password"
            show-password
            @update:model-value="v => setFieldValue(f, v)"
          />
          <el-input v-else :model-value="fieldValue(f)" :placeholder="f.placeholder" @update:model-value="v => setFieldValue(f, v)" />
        </el-form-item>
        <el-form-item v-if="currentType" label="URL 预览">
          <span class="url-preview">{{ urlPreview }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="testForm" :loading="testing">测试连接</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import http from '../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const dbTypes = ref([])
const dialogVisible = ref(false)
const testing = ref(false)

// 具名字段（DataSource 上的列）
const NAMED_KEYS = ['id', 'name', 'type', 'host', 'port', 'username', 'password', 'extraParams', 'defaultDb', 'props']
// props（类型特有字段）的本地模型，保存时序列化回 form.props
const propsModel = reactive({})

const form = reactive({
  id: '', name: '', type: 'mysql', host: '', port: 3306,
  username: '', password: '', extraParams: '', defaultDb: '', props: ''
})

const currentType = computed(() => dbTypes.value.find(t => t.type === form.type) || null)
const fields = computed(() => (currentType.value && currentType.value.fields) || [])
const urlPreview = computed(() => {
  const t = currentType.value
  if (!t || !t.urlPreview) return '-'
  return t.urlPreview
    .replace('{host}', form.host || 'host')
    .replace('{port}', form.port != null ? form.port : 'port')
    .replace('{database}', form.defaultDb || 'database')
    .replace('{default_db}', form.defaultDb || 'database')
})

onMounted(() => {
  load()
  http.get('/meta/db-types').then(d => { dbTypes.value = d || [] })
})

function load() {
  http.get('/datasources').then(d => list.value = d || [])
}

function typeLabel(type) {
  const t = dbTypes.value.find(x => x.type === (type || 'mysql'))
  return t ? t.displayName : (type || 'mysql')
}

/** 动态字段的读写：inProps 的字段走 propsModel，其余直接绑 DataSource 具名列 */
function fieldValue(f) {
  const target = f.inProps ? propsModel : form
  if (target[f.key] === undefined) {
    target[f.key] = f.defaultValue !== undefined ? f.defaultValue : (f.type === 'number' ? null : '')
  }
  return target[f.key]
}

function setFieldValue(f, val) {
  const target = f.inProps ? propsModel : form
  target[f.key] = val
}

function syncPropsFromForm() {
  if (!form.props) return
  try {
    const parsed = JSON.parse(form.props)
    Object.keys(parsed || {}).forEach(k => { propsModel[k] = parsed[k] })
  } catch (e) {
    // 非法 JSON 忽略，避免编辑既有脏数据时打不开弹窗
  }
}

function buildProps() {
  const out = {}
  fields.value.filter(f => f.inProps).forEach(f => {
    const v = propsModel[f.key]
    if (v !== undefined && v !== null && String(v).trim() !== '') out[f.key] = v
  })
  return Object.keys(out).length ? JSON.stringify(out) : null
}

function resetForm(type) {
  NAMED_KEYS.forEach(k => { form[k] = '' })
  Object.keys(propsModel).forEach(k => delete propsModel[k])
  form.type = type || (dbTypes.value[0] && dbTypes.value[0].type) || 'mysql'
  form.port = currentType.value ? currentType.value.defaultPort : 3306
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row) {
  resetForm(row.type)
  Object.assign(form, row)
  syncPropsFromForm()
  dialogVisible.value = true
}

/** 切换类型时带出该类型的默认端口（编辑态不覆盖用户已有端口） */
function onTypeChange() {
  const t = currentType.value
  if (!t) return
  if (!form.id) {
    form.port = t.defaultPort
  }
}

function validate() {
  const t = currentType.value
  if (!t) {
    ElMessage.error('请选择数据源类型')
    return false
  }
  if (!form.name || !String(form.name).trim()) {
    ElMessage.error('请填写名称')
    return false
  }
  for (const f of fields.value) {
    if (!f.required) continue
    const v = f.inProps ? propsModel[f.key] : form[f.key]
    if (v === undefined || v === null || String(v).trim() === '') {
      ElMessage.error(`请填写${f.label}`)
      return false
    }
  }
  return true
}

function payload() {
  const body = { ...form }
  body.props = buildProps()
  return body
}

async function testForm() {
  if (!validate()) return
  testing.value = true
  try {
    await http.post('/datasources/test', payload())
    ElMessage.success('连接成功')
  } finally {
    testing.value = false
  }
}

async function testSaved(row) {
  await http.post(`/datasources/${row.id}/test`)
  ElMessage.success('连接成功')
}

async function save() {
  if (!validate()) return
  if (form.id) {
    await http.put(`/datasources/${form.id}`, payload())
  } else {
    await http.post('/datasources', payload())
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  load()
}

async function del(row) {
  await http.delete(`/datasources/${row.id}`)
  ElMessage.success('删除成功')
  load()
}

function fmtTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.title { font-size: 16px; font-weight: 600; }
.url-preview { color: #909399; font-size: 12px; word-break: break-all; }
</style>
