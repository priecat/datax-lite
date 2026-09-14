<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <el-icon :size="40" color="#409eff"><Promotion /></el-icon>
        <h1>DataX Lite</h1>
        <p>数据同步管理工具</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleLogin">
        <el-form-item prop="account">
          <el-input v-model="form.account" placeholder="请输入账号" prefix-icon="User" size="large" />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            prefix-icon="Lock"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item>
          <div class="agree-row" v-if="loginDialog">
            <el-checkbox :model-value="agreed" size="small" @click.prevent="noticeVisible = true">
              我已阅读并同意
              <el-link type="primary" :underline="false" @click.stop="noticeVisible = true">《使用声明与风险提示》</el-link>
            </el-checkbox>
          </div>
          <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="handleLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 免责声明：点《免责声明》链接弹出，内容可滚动时必须滚到底部才能点同意 -->
    <el-dialog v-model="noticeVisible" title="使用声明与风险提示" width="520px" :close-on-click-modal="false" append-to-body @opened="onNoticeOpened">
      <div ref="noticeBodyRef" class="notice-body" @scroll.passive="checkNoticeScrolled">
        <div class="notice-item" v-for="(item, i) in notices" :key="i">
          <span class="notice-title">{{ item.title }}</span>
          <p>{{ item.content }}</p>
        </div>
      </div>
      <template #footer>
        <el-button @click="declineNotice">取 消</el-button>
        <el-button type="primary" :disabled="!noticeScrolled" @click="agreeNotice">
          {{ noticeScrolled ? '同 意' : '请先阅读至底部' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api'
import { setToken, setUser } from '../auth'

const router = useRouter()
const route = useRoute()
const formRef = ref(null)
const loading = ref(false)
const noticeVisible = ref(false)
const agreed = ref(false)

// 强制阅读：弹窗内容溢出出现滚动条时，必须滚动到底部才允许点「同意」
const noticeBodyRef = ref(null)
const noticeScrolled = ref(false)
const loginDialog = import.meta.env.VITE_LOGIN_DIALOG === 'true'

const checkNoticeScrolled = () => {
  const el = noticeBodyRef.value
  if (!el) return
  // 无滚动条（scrollHeight === clientHeight）或已到底部（留 4px 容差）均视为阅读完毕
  noticeScrolled.value = el.scrollHeight - el.scrollTop - el.clientHeight <= 4
}

const onNoticeOpened = () => {
  noticeScrolled.value = false
  nextTick(checkNoticeScrolled)
}

const notices = [
  {
    title: '项目定位',
    content:
            '本工具基于 DataX 二次开发，适合本地、开发环境或测试环境中的短期、非重要数据同步。它并非为在服务器上长期稳定运行而设计，请勿用于生产环境、承载线上业务、处理敏感数据或重要的高价值数据。生产环境请使用 DataX 官方发行版本。'
  },
  {
    title: '高风险操作',
    content: '数据库对拷属于高风险操作。本工具不可避免的会对库表数据进行修改删除操作，若操作不当或执行中途失败，可能造成不可逆的数据丢失。使用本工具前请务必先同时备份源端与目标端，并在测试环境完成充分验证。'
  },
  {title: '安全提示', content:
            '本工具会保存数据库连接凭证。对数据源端请使用只读账号，对目标端使用仅具备同步所需写权限的独立账号，请勿使用 root 等管理权限账号。请勿将服务直接暴露到公共互联网，首次使用请立即修改本工具默认管理员账号密码与 JWT 签名密钥。'},
  {
    title: '免责声明',
    content:
            '本项目不提供任何形式的商业支持或可用性、可靠性、安全性承诺。对于因使用本项目而导致的任何直接或间接损失（包括但不限于数据丢失、服务中断、安全事件），本项目作者不承担任何责任，使用前请自行评估风险并完成充分验证。'
  }
]

const form = reactive({
  account: '',
  password: ''
})

const rules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 登录前校验表单 + 免责声明勾选；未勾选不允许登录
const handleLogin = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (loginDialog){
    if (!agreed.value) {
      ElMessage.warning('请先阅读并同意《使用声明与风险提示》')
      return
    }
  }
  await doLogin()
}

// 弹窗内点同意：勾选复选框并关闭
const agreeNotice = () => {
  agreed.value = true
  noticeVisible.value = false
}

// 弹窗内点取消：去掉勾选并关闭
const declineNotice = () => {
  agreed.value = false
  noticeVisible.value = false
}

const doLogin = async () => {
  loading.value = true
  try {
    const data = await http.post('/auth/login', form)
    setToken(data.token)
    setUser(data.user)
    noticeVisible.value = false
    ElMessage.success('登录成功')
    router.push(route.query.redirect || '/tasks')
  } catch (e) {
    // 错误已在拦截器中统一提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1c2b46 0%, #2c4a7c 60%, #3a6ea5 100%);
}

.login-card {
  width: 380px;
  padding: 48px 40px 40px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(0, 21, 41, 0.35);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-header h1 {
  font-size: 24px;
  font-weight: 600;
  color: #1a1a1a;
  margin: 12px 0 6px;
  letter-spacing: 1px;
}

.login-header p {
  font-size: 13px;
  color: #999;
  margin: 0;
}

.login-btn {
  width: 100%;
  border-radius: 6px;
  height: 40px;
  font-size: 15px;
}

.notice-body {
  max-height: 46vh;
  overflow: auto;
  padding-right: 4px;
}

.notice-item { margin-bottom: 14px; }

.notice-item:last-child { margin-bottom: 0; }

.notice-title {
  display: inline-block;
  font-weight: 600;
  color: #e6a23c;
  margin-bottom: 4px;
}

.notice-item p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
}

.agree-row {
  width: 100%;
  margin-bottom: 12px;
  display: flex;
  justify-content: center;
}

/* 复选框文字与链接统一字号、垂直居中对齐（el-link 默认 14px 且基线偏移会错位） */
.agree-row :deep(.el-checkbox__label) {
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  vertical-align: middle;
}

.agree-row :deep(.el-link) {
  font-size: 13px;
  vertical-align: middle;
  top: 0;
  align-items: center;
}
</style>
