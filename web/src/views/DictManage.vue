<template>
  <el-card class="fill-card">
    <el-tabs v-model="tab" class="dict-tabs">
      <!-- ════════ Tab 1：字段类型字典 ════════ -->
      <el-tab-pane label="字段类型字典" name="values">
        <div class="values-layout">
          <!-- 左：字典类型 -->
          <div class="pane">
            <div class="pane-head">
              <span class="pane-title">字典类型</span>
              <el-button type="primary" icon="Plus" size="small" @click="openTypeCreate">新增</el-button>
            </div>
            <div class="table-wrap">
              <el-table :data="types" border stripe height="100%" highlight-current-row
                        @current-change="onTypeSelect">
                <el-table-column prop="code" label="编码" min-width="170" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span :class="{ 'row-selected': currentType?.id === row.id }">{{ row.code }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
                <el-table-column label="预设" width="70">
                  <template #default="{ row }">
                    <el-tag v-if="row.isSystem === 1" type="info" size="small">系统</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="160" fixed="right">
                  <template #default="{ row }">
                    <el-button size="small" @click="openTypeEdit(row)">编辑</el-button>
                    <el-popconfirm title="确认删除该字典及其全部条目？" @confirm="delType(row)">
                      <template #reference>
                        <el-button size="small" type="danger" :disabled="row.isSystem === 1">删除</el-button>
                      </template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
          <!-- 右：字典条目 -->
          <div class="pane">
            <div class="pane-head">
              <span class="pane-title">条目 - {{ currentType?.name || currentType?.code || '（请选择左侧字典）' }}</span>
              <el-button type="primary" icon="Plus" size="small" :disabled="!currentType"
                         @click="openValueCreate">新增条目</el-button>
            </div>
            <div class="table-wrap">
              <el-table :data="values" border stripe height="100%">
                <el-table-column prop="label" label="类型名（key）" min-width="180" show-overflow-tooltip />
                <el-table-column prop="value" label="默认长度（可空）" min-width="130" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.value || '—' }}</template>
                </el-table-column>
                <el-table-column prop="sort" label="排序" width="70" />
                <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
                <el-table-column label="预设" width="70">
                  <template #default="{ row }">
                    <el-tag v-if="row.isSystem === 1" type="info" size="small">系统</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="160" fixed="right">
                  <template #default="{ row }">
                    <el-button size="small" @click="openValueEdit(row)">编辑</el-button>
                    <el-popconfirm title="确认删除该条目？" @confirm="delValue(row)">
                      <template #reference>
                        <el-button size="small" type="danger">删除</el-button>
                      </template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ════════ Tab 2：类型映射规则 ════════ -->
      <el-tab-pane label="类型映射规则" name="maps">
        <div class="toolbar">
          <span class="map-hint">按「源库 -> 目标库」方向分组，同名源类型覆盖式唯一。未配置的方向走恒等回退。</span>
          <div class="map-actions">
            <el-select v-model="currentGroup" filterable allow-create default-first-option
                       placeholder="选择或输入分组，如 postgresql->mysql" style="width: 280px"
                       @change="loadMaps">
              <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
            </el-select>
            <el-button type="primary" icon="Plus" :disabled="!currentGroup" @click="openMapCreate">新增规则</el-button>
          </div>
        </div>
        <div class="table-wrap">
          <el-table :data="maps" border stripe height="100%">
            <el-table-column prop="sourceType" label="源类型（基名）" min-width="200" show-overflow-tooltip />
            <el-table-column prop="targetValue" label="目标 DDL 类型" min-width="200" show-overflow-tooltip />
            <el-table-column prop="targetLabel" label="目标显示名" min-width="140" show-overflow-tooltip />
            <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
            <el-table-column label="预设" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.isSystem === 1" type="info" size="small">系统</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="openMapEdit(row)">编辑</el-button>
                <el-popconfirm title="确认删除该映射规则？" @confirm="delMap(row)">
                  <template #reference>
                    <el-button size="small" type="danger">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 字典类型 新增/编辑 -->
    <el-dialog v-model="typeVisible" :title="typeForm.id ? '编辑字典类型' : '新增字典类型'" width="440px"
               :close-on-click-modal="false">
      <el-form ref="typeFormRef" :model="typeForm" :rules="typeRules" label-width="90px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="typeForm.code" :disabled="!!typeForm.id"
                    placeholder="约定 db_{数据库类型}_field，如 db_mysql_field" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="typeForm.name" placeholder="如 MySQL 字段类型" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="typeForm.remark" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveType">确定</el-button>
      </template>
    </el-dialog>

    <!-- 字典条目 新增/编辑 -->
    <el-dialog v-model="valueVisible" :title="valueForm.id ? '编辑条目' : '新增条目'" width="440px"
               :close-on-click-modal="false">
      <el-form ref="valueFormRef" :model="valueForm" :rules="valueRules" label-width="90px">
        <el-form-item label="类型名" prop="label">
          <el-input v-model="valueForm.label" placeholder="基础类型（key），如 varchar" />
        </el-form-item>
        <el-form-item label="默认长度" prop="value">
          <el-input v-model="valueForm.value" placeholder="可空；选中时填入长度列，如 255 或 10,2" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="valueForm.sort" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="valueForm.remark" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="valueVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveValue">确定</el-button>
      </template>
    </el-dialog>

    <!-- 映射规则 新增/编辑 -->
    <el-dialog v-model="mapVisible" :title="mapForm.id ? '编辑映射规则' : '新增映射规则'" width="460px"
               :close-on-click-modal="false">
      <el-form ref="mapFormRef" :model="mapForm" :rules="mapRules" label-width="110px">
        <el-form-item label="方向分组">
          <el-input v-model="currentGroup" disabled />
        </el-form-item>
        <el-form-item label="源类型" prop="sourceType">
          <el-input v-model="mapForm.sourceType" :disabled="!!mapForm.id"
                    placeholder="源类型基名（小写），如 character varying" />
        </el-form-item>
        <el-form-item label="目标 DDL 类型" prop="targetValue">
          <el-select v-if="targetDictOptions.length" v-model="mapForm.targetValue" filterable
                     allow-create default-first-option placeholder="选择或输入目标类型">
            <el-option v-for="o in targetDictOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input v-else v-model="mapForm.targetValue" placeholder="目标 DDL 类型，如 longtext" />
        </el-form-item>
        <el-form-item label="目标显示名">
          <el-input v-model="mapForm.targetLabel" placeholder="选填，仅管理页参考" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="mapForm.remark" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mapVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMap">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'

const tab = ref('values')

// ── 字典类型 ──
const types = ref([])
const currentType = ref(null)
const typeVisible = ref(false)
const typeFormRef = ref(null)
const typeForm = reactive({ id: '', code: '', name: '', remark: '' })
const typeRules = {
  code: [{ required: true, message: '请输入字典编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入字典名称', trigger: 'blur' }]
}

const loadTypes = async () => {
  types.value = await http.get('/dict/types')
  // 当前选中项被删除时清空右侧
  if (currentType.value && !types.value.some(t => t.id === currentType.value.id)) {
    currentType.value = null
    values.value = []
  }
}

const onTypeSelect = (row) => {
  currentType.value = row || null
  loadValues()
}

const openTypeCreate = () => {
  Object.assign(typeForm, { id: '', code: '', name: '', remark: '' })
  typeFormRef.value?.clearValidate()
  typeVisible.value = true
}

const openTypeEdit = (row) => {
  Object.assign(typeForm, { id: row.id, code: row.code, name: row.name, remark: row.remark })
  typeFormRef.value?.clearValidate()
  typeVisible.value = true
}

const saveType = async () => {
  const valid = await typeFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (typeForm.id) {
      await http.put(`/dict/types/${typeForm.id}`, typeForm)
      ElMessage.success('修改成功')
    } else {
      await http.post('/dict/types', typeForm)
      ElMessage.success('新增成功')
    }
    typeVisible.value = false
    loadTypes()
  } catch (e) {
    // 错误已在拦截器中统一提示
  } finally {
    saving.value = false
  }
}

const delType = async (row) => {
  await http.delete(`/dict/types/${row.id}`)
  ElMessage.success('删除成功')
  loadTypes()
}

// ── 字典条目 ──
const values = ref([])
const valueVisible = ref(false)
const valueFormRef = ref(null)
const valueForm = reactive({ id: '', dictCode: '', label: '', value: '', sort: 0, remark: '' })
const valueRules = {
  label: [{ required: true, message: '请输入类型名', trigger: 'blur' }]
}

const loadValues = async () => {
  if (!currentType.value) {
    values.value = []
    return
  }
  values.value = await http.get('/dict/values', { params: { dictCode: currentType.value.code } })
}

const openValueCreate = () => {
  Object.assign(valueForm, { id: '', dictCode: currentType.value.code, label: '', value: '', sort: values.value.length + 1, remark: '' })
  valueFormRef.value?.clearValidate()
  valueVisible.value = true
}

const openValueEdit = (row) => {
  Object.assign(valueForm, { id: row.id, dictCode: row.dictCode, label: row.label, value: row.value, sort: row.sort || 0, remark: row.remark })
  valueFormRef.value?.clearValidate()
  valueVisible.value = true
}

const saveValue = async () => {
  const valid = await valueFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (valueForm.id) {
      await http.put(`/dict/values/${valueForm.id}`, valueForm)
      ElMessage.success('修改成功')
    } else {
      await http.post('/dict/values', valueForm)
      ElMessage.success('新增成功')
    }
    valueVisible.value = false
    loadValues()
  } catch (e) {
    // 错误已在拦截器中统一提示
  } finally {
    saving.value = false
  }
}

const delValue = async (row) => {
  await http.delete(`/dict/values/${row.id}`)
  ElMessage.success('删除成功')
  loadValues()
}

// ── 类型映射 ──
const groups = ref([])
const currentGroup = ref('')
const maps = ref([])
const mapVisible = ref(false)
const mapFormRef = ref(null)
const mapForm = reactive({ id: '', groupName: '', sourceType: '', targetValue: '', targetLabel: '', remark: '' })
const mapRules = {
  sourceType: [{ required: true, message: '请输入源类型基名', trigger: 'blur' }],
  targetValue: [{ required: true, message: '请选择或输入目标 DDL 类型', trigger: 'blur' }]
}

// 目标方向的字典条目作为「目标 DDL 类型」候选（如 postgresql->mysql 取 db_mysql_field）
const targetDictOptions = ref([])

const loadTargetDictOptions = async () => {
  targetDictOptions.value = []
  const g = (currentGroup.value || '').toLowerCase()
  const m = g.match(/^([a-z0-9_-]+)->([a-z0-9_-]+)$/)
  if (!m) return
  const code = `db_${m[2]}_field`
  const found = types.value.find(t => t.code === code)
  if (!found) return
  try {
    // 字典 KV：label=基础类型, value=默认长度；规则需要完整 DDL 类型，合成 varchar -> varchar(255)
    const vals = await http.get('/dict/values', { params: { dictCode: code } })
    targetDictOptions.value = vals.map(v => {
      const full = v.value ? `${v.label}(${v.value})` : v.label
      return { label: full, value: full }
    })
  } catch (e) {
    targetDictOptions.value = []
  }
}

const loadGroups = async () => {
  groups.value = await http.get('/type-maps/groups')
}

const loadMaps = async () => {
  loadTargetDictOptions()
  if (!currentGroup.value) {
    maps.value = []
    return
  }
  maps.value = await http.get('/type-maps', { params: { group: currentGroup.value } })
}

const openMapCreate = () => {
  Object.assign(mapForm, { id: '', groupName: currentGroup.value, sourceType: '', targetValue: '', targetLabel: '', remark: '' })
  mapFormRef.value?.clearValidate()
  mapVisible.value = true
}

const openMapEdit = (row) => {
  Object.assign(mapForm, {
    id: row.id, groupName: row.groupName, sourceType: row.sourceType,
    targetValue: row.targetValue, targetLabel: row.targetLabel, remark: row.remark
  })
  mapFormRef.value?.clearValidate()
  mapVisible.value = true
}

const saveMap = async () => {
  const valid = await mapFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (mapForm.id) {
      await http.put(`/type-maps/${mapForm.id}`, mapForm)
      ElMessage.success('修改成功')
    } else {
      await http.post('/type-maps', { ...mapForm, groupName: currentGroup.value })
      ElMessage.success('新增成功')
    }
    mapVisible.value = false
    loadMaps()
    loadGroups()
  } catch (e) {
    // 错误已在拦截器中统一提示
  } finally {
    saving.value = false
  }
}

const delMap = async (row) => {
  await http.delete(`/type-maps/${row.id}`)
  ElMessage.success('删除成功')
  loadMaps()
  loadGroups()
}

const saving = ref(false)

onMounted(async () => {
  await loadTypes()
  await loadGroups()
  if (groups.value.length && !currentGroup.value) {
    currentGroup.value = groups.value[0]
  }
  await loadMaps()
})

// 切到映射 Tab 时刷新一次分组（其它页面可能新增过）
watch(tab, (t) => {
  if (t === 'maps') {
    loadGroups().then(() => {
      if (!currentGroup.value && groups.value.length) currentGroup.value = groups.value[0]
      loadMaps()
    })
  }
})
</script>

<style scoped>
.dict-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.dict-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
.dict-tabs :deep(.el-tab-pane) {
  height: 100%;
}
.values-layout {
  display: flex;
  gap: 12px;
  height: 100%;
}
.values-layout .pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.pane-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}
.row-selected {
  font-weight: 600;
  color: #409eff;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.map-hint {
  font-size: 13px;
  color: #909399;
}
.map-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
