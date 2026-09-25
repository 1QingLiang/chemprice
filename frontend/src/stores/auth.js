import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, getUserInfo } from '../api/index'

export const useAuthStore = defineStore('auth', () => {
  // 状态
  const token = ref(localStorage.getItem('token') || '')
  const user = ref(JSON.parse(localStorage.getItem('user') || '{}'))

  // 计算属性
  const isAuthenticated = computed(() => !!token.value)
  const userInfo = computed(() => user.value)
  const canExport = computed(() => {
    return user.value?.role === 'ADMIN' || user.value?.exportPermission === 1 || user.value?.exportPermission === '1'
  })

  // 方法
  const login = async (credentials) => {
    const res = await apiLogin(credentials)
    if (res && res.code === 200) {
      const d = res.data
      setAuth(d.token, {
        username: d.username,
        nickname: d.nickname,
        role: d.role,
        exportPermission: d.exportPermission
      })
    }
    return res
  }

  const setAuth = (newToken, userData) => {
    token.value = newToken
    user.value = userData
    localStorage.setItem('token', newToken)
    localStorage.setItem('user', JSON.stringify(userData))
  }

  // 用后端最新用户信息刷新本地缓存（角色/权限变更后无需重新登录即可生效）
  const refreshUser = async () => {
    if (!token.value) return
    try {
      const res = await getUserInfo()
      if (res && res.code === 200) {
        const d = res.data
        const fresh = {
          username: d.username || user.value.username,
          nickname: d.nickname || user.value.nickname,
          role: d.role || user.value.role,
          exportPermission: d.exportPermission
        }
        setAuth(token.value, fresh)
      }
    } catch (e) {
      // 401 已由 axios 拦截器统一处理（清登录态并跳登录页）
    }
  }

  const clearAuth = () => {
    token.value = ''
    user.value = {}
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  const logout = () => {
    clearAuth()
    window.location.href = '/login'
  }

  return {
    token,
    user,
    isAuthenticated,
    userInfo,
    canExport,
    login,
    setAuth,
    refreshUser,
    clearAuth,
    logout
  }
})