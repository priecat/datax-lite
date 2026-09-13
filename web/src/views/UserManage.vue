<template>
  <el-card class="fill-card">
    <div class="toolbar">
      <span class="title">用户管理</span>
      <el-button type="primary" icon="Plus" @click="openCreate">新增用户</el-button>
    </div>

    <div class="table-wrap">
      <el-table :data="list" border stripe height="100%">
      <el-table-column prop="account" label="账号" min-width="120" />
      <el-table-column prop="name" label="姓名" min-width="120" />
      <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === '1' ? 'success' : 'danger'" size="small">
            {{ row.status === '1' ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="warning" @click="openReset(row)">重置密码</el-button>
          <el-popconfirm title="确认删除该用户？" @confirm="del(row)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <!-- 新增/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑用户' : '新增用户'" width="440px"
               :close-on-click-modal="false">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="80px">
        <el-form-item label="账号" prop="account">
          <el-input v-model="editForm.account" :disabled="!!editForm.id" placeholder="登录账号" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="editForm.name" placeholder="用户姓名" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" placeholder="选填" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-switch v-model="editForm.status" active-value="1" inactive-value="0"
                     active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-form-item v-if="!editForm.id" label="初始密码" prop="password">
          <el-input v-model="editForm.password" type="password" show-password placeholder="不少于 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetVisible" :title="'重置密码 - ' + (resetUser?.name || '')" width="400px"
               :close-on-click-modal="false">
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="80px">
        <el-form-item label="新密码" prop="password">
          <el-input v-model="resetForm.password" type="password" show-password placeholder="不少于 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doReset">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'

const list = ref([])
const editVisible = ref(false)
const resetVisible = ref(false)
const saving = ref(false)
const editFormRef = ref(null)
const resetFormRef = ref(null)
const editForm = reactive({ id: '', account: '', name: '', email: '', status: '1', password: '' })
const resetUser = ref(null)
const resetForm = reactive({ password: '' })

const fmtTime = (t) => {
  if (!t) return '-'
  const d = new Date(typeof t === 'number' ? t : Date.parse(t))
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const load = async () => {
  list.value = await http.get('/users')
}

const editRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

const resetRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

const openCreate = () => {
  Object.assign(editForm, { id: '', account: '', name: '', email: '', status: '1', password: '' })
  editFormRef.value?.clearValidate()
  editVisible.value = true
}

const openEdit = (row) => {
  Object.assign(editForm, { id: row.id, account: row.account, name: row.name, email: row.email, status: row.status, password: '' })
  editFormRef.value?.clearValidate()
  editVisible.value = true
}

const save = async () => {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editForm.id) {
      await http.put(`/users/${editForm.id}`, {
        name: editForm.name, email: editForm.email, status: editForm.status
      })
      ElMessage.success('修改成功')
    } else {
      await http.post('/users', {
        account: editForm.account, name: editForm.name, email: editForm.email,
        status: editForm.status, password: editForm.password
      })
      ElMessage.success('新增成功')
    }
    editVisible.value = false
    load()
  } catch (e) {
    // 错误已在拦截器中统一提示
  } finally {
    saving.value = false
  }
}

const openReset = (row) => {
  resetUser.value = row
  resetForm.password = ''
  resetFormRef.value?.clearValidate()
  resetVisible.value = true
}

const doReset = async () => {
  const valid = await resetFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await http.post(`/users/${resetUser.value.id}/reset-password`, { password: resetForm.password })
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } catch (e) {
    // 错误已在拦截器中统一提示
  } finally {
    saving.value = false
  }
}

const del = async (row) => {
  await http.delete(`/users/${row.id}`)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.toolbar .title {
  font-size: 15px;
  font-weight: 600;
}
</style>
