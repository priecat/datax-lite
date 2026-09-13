/**
 * 登录态管理：token 与用户信息的本地存取
 */
const TOKEN_KEY = 'token'
const USER_KEY = 'user'

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ''

export const setToken = (token) => localStorage.setItem(TOKEN_KEY, token)

export const getUser = () => {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY))
  } catch (e) {
    return null
  }
}

export const setUser = (user) => localStorage.setItem(USER_KEY, JSON.stringify(user))

export const clearAuth = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
