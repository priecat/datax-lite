import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from './auth'
import router from './router'

const http = axios.create({ baseURL: '/api', timeout: 120000 })

// 请求拦截器：自动携带登录 token
http.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

// 未登录/登录过期：清除本地登录态并跳转登录页
const toLogin = () => {
  clearAuth()
  if (router.currentRoute.value.path !== '/login') {
    router.push('/login')
  }
}

http.interceptors.response.use(
  resp => {
    const r = resp.data
    if (r && typeof r.code === 'number') {
      if (r.code === 0) {
        return r.data
      }
      if (r.code === 401) {
        toLogin()
        return Promise.reject(new Error(r.msg || '未登录'))
      }
      ElMessage.error(r.msg || '请求失败')
      return Promise.reject(new Error(r.msg || '请求失败'))
    }
    return r
  },
  err => {
    if (err.response?.status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      toLogin()
      return Promise.reject(err)
    }
    const msg = err.response?.data?.msg || err.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http
