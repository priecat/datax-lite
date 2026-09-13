<template>
  <el-container style="height: 100%">
    <el-aside width="200px" class="aside">
      <div class="logo">
        <el-icon :size="22"><Promotion /></el-icon>
        <span>DataX Lite</span>
      </div>
      <el-menu :default-active="$route.path" router background-color="#001529" text-color="#c7cbd4"
               active-text-color="#ffffff" style="border-right: none">
        <el-menu-item index="/datasources">
          <el-icon><Coin /></el-icon><span>数据源管理</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><Sort /></el-icon><span>数据传输</span>
        </el-menu-item>
        <el-menu-item index="/schedules">
          <el-icon><Timer /></el-icon><span>定时任务</span>
        </el-menu-item>
        <el-menu-item index="/logs">
          <el-icon><Document /></el-icon><span>执行日志</span>
        </el-menu-item>
        <el-menu-item index="/plugins">
          <el-icon><Box /></el-icon><span>插件管理</span>
        </el-menu-item>
        <el-menu-item index="/dicts">
          <el-icon><Collection /></el-icon><span>类型字典</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon><span>用户管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header height="56px" class="header">
        <span class="header-title">{{ $route.meta.title }}</span>
        <el-dropdown trigger="click" @command="handleCommand">
          <span class="user-info">
<!--            <el-avatar :size="28" class="avatar">{{ avatarText }}</el-avatar>-->
            <span class="user-name">{{ user?.name || user?.account || '未知用户' }}</span>
            <el-icon :size="12"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="changePassword" icon="Key">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" icon="SwitchButton" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main style="padding: 16px">
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <!-- 修改密码 -->
  <el-dialog v-model="pwdDialogVisible" title="修改密码" width="420px" :close-on-click-modal="false">
    <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="80px">
      <el-form-item label="旧密码" prop="oldPassword">
        <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入旧密码" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="不少于 6 位" />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="pwdDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="pwdLoading" @click="submitPassword">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api'
import { getUser, clearAuth } from '../auth'

const router = useRouter()
const user = ref(getUser())
const avatarText = computed(() => (user.value?.name || user.value?.account || '?').slice(0, 1))

const handleCommand = (command) => {
  if (command === 'logout') {
    clearAuth()
    router.push('/login')
  } else if (command === 'changePassword') {
    resetPwdForm()
    pwdDialogVisible.value = true
  }
}

// 修改密码
const pwdFormRef = ref(null)
const pwdDialogVisible = ref(false)
const pwdLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const resetPwdForm = () => {
  Object.assign(pwdForm, { oldPassword: '', newPassword: '', confirmPassword: '' })
  pwdFormRef.value?.clearValidate()
}

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '新密码长度不能少于 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

const submitPassword = async () => {
  const valid = await pwdFormRef.value?.validate().catch(() => false)
  if (!valid) return
  pwdLoading.value = true
  try {
    await http.post('/auth/change_password', {
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    })
    pwdDialogVisible.value = false
    ElMessage.success('密码修改成功，请重新登录')
    clearAuth()
    router.push('/login')
  } catch (e) {
    // 错误已在拦截器中统一提示
  } finally {
    pwdLoading.value = false
  }
}
</script>

<style scoped>
.aside {
  background: #001529;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
}
.header-title {
  font-size: 15px;
  font-weight: 600;
  color: #333;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
.avatar {
  background: #409eff;
  color: #fff;
  font-size: 14px;
}
.user-name {
  font-size: 14px;
  color: #333;
}
</style>
