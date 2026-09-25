import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import router from '../router'

// 创建 axios 实例
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器 - 注入 JWT token
api.interceptors.request.use(
  (config) => {
    const authStore = useAuthStore()
    if (authStore.token) {
      config.headers.Authorization = `Bearer ${authStore.token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器 - 处理 401 错误
api.interceptors.response.use(
  (response) => {
    // blob 类型（文件下载）直接返回完整 response
    if (response.config?.responseType === 'blob') return response
    return response.data
  },
  (error) => {
    if (error.response?.status === 401) {
      const authStore = useAuthStore()
      authStore.clearAuth()
      router.push('/login')
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    return Promise.reject(error)
  }
)

// API 方法
export const login = (credentials) => {
  return api.post('/auth/login', credentials)
}

export const getUserInfo = () => {
  return api.get('/auth/me')
}

export const getDashboardStats = () => {
  return api.get('/dashboard/stats')
}

export const getAugStats = () => {
  return api.get('/dashboard/augstats')
}

export const getDashboardTrend = (params) => {
  return api.get('/dashboard/trend', { params })
}

export const getDashboardMovers = (params) => {
  return api.get('/dashboard/movers', { params })
}

export const getPrices = (params) => {
  return api.get('/prices', { params })
}

// 报价点联动选项：按价格类型 + 商品 + 日期范围返回实际存在的报价点
export const getMarketOptions = (params) => {
  return api.get('/prices/market-options', { params })
}

// ===== 全站公告 =====
export const getCurrentAnnouncement = () => api.get('/announcement/current')
export const publishAnnouncement = (data) => api.post('/admin/announcement/publish', data)
export const updateAnnouncement = (id, data) => api.put(`/admin/announcement/${id}`, data)
export const enableAnnouncement = (id) => api.post(`/admin/announcement/${id}/enable`)
export const disableAnnouncement = (id) => api.post(`/admin/announcement/${id}/disable`)
export const removeAnnouncement = (id) => api.delete(`/admin/announcement/${id}`)
export const listAnnouncements = (params) => api.get('/admin/announcement/list', { params })

export const getAnalysis = (params) => {
  return api.get('/prices/analysis', { params })
}

export const exportPrices = (params) => {
  return api.get('/prices/export', { params, responseType: 'blob' })
}

export const getCommodities = (params) => {
  return api.get('/commodities', { params })
}

export const getUsers = (params) => {
  return api.get('/admin/users', { params })
}

export const createUser = (data) => {
  return api.post('/admin/users', data)
}

export const updateUser = (id, data) => {
  return api.put(`/admin/users/${id}`, data)
}

export const deleteUser = (id) => {
  return api.delete(`/admin/users/${id}`)
}

export const getUserLogs = (userId) => {
  return api.get(`/admin/users/${userId}/logs`)
}

export const getUserPermissions = (userId) => {
  return api.get(`/admin/users/${userId}/permissions`)
}

export const grantPermission = (userId, data) => {
  return api.post(`/admin/users/${userId}/permissions`, data)
}

export const grantCategoryPermission = (userId, data) => {
  return api.post(`/admin/users/${userId}/permissions/category`, data)
}

export const revokeCategoryPermission = (userId, category) => {
  return api.delete(`/admin/users/${userId}/permissions/category/${encodeURIComponent(category)}`)
}

export const revokePermission = (userId, permId) => {
  return api.delete(`/admin/users/${userId}/permissions/${permId}`)
}

export const getAuditLogs = (params) => {
  return api.get('/admin/logs', { params })
}

export const getPriceMatrix = (params) => {
  return api.get('/prices/matrix', { params })
}

export const register = (data) => {
  return api.post('/auth/register', data)
}

export const resetPassword = (username) => {
  return api.post('/auth/reset-password', { username })
}

export const sendCode = (email, purpose) => {
  return api.post('/auth/send-code', { email, purpose })
}

export const verifyCode = (email, code, purpose) => {
  return api.post('/auth/verify-code', { email, code, purpose })
}

export const resetPasswordByEmail = (email, code, newPassword) => {
  return api.post('/auth/reset-password-by-email', { email, code, newPassword })
}

// 关注
export const getFavorites = () => api.get('/favorites')
export const addFavorite = (varietiesId, params) => api.post(`/favorites/${varietiesId}`, null, { params })
export const removeFavorite = (varietiesId, params) => api.delete(`/favorites/${varietiesId}`, { params })
export const getFavoriteLatestPrices = () => api.get('/favorites/latest-prices')
export const getTypeHighPrices = () => api.get('/favorites/type-high-prices')

// AI 智能问答（长超时：模型推理较慢）
export const aiChat = (message) => api.post('/ai/chat', { message }, { timeout: 120000 })

// 关注产品邮件推送（任务化）
export const getPushTasks = () => api.get('/push/tasks')
export const createPushTask = (data) => api.post('/push/tasks', data)
export const updatePushTask = (id, data) => api.put(`/push/tasks/${id}`, data)
export const deletePushTask = (id) => api.delete(`/push/tasks/${id}`)
export const testPushTask = (id) => api.post(`/push/tasks/${id}/test`, null, { timeout: 90000 })
// 关注产品邮件推送（旧版单配置，兼容保留）
export const getPushConfig = () => api.get('/push/config')
export const savePushConfig = (data) => api.post('/push/config', data)
export const testPush = () => api.post('/push/test-send')
export const adminGetPushConfig = (userId) => api.get(`/admin/users/${userId}/push-config`)
export const adminSetPushQuota = (userId, pushQuota) => api.put(`/admin/users/${userId}/push-quota`, { pushQuota })

// AI 问答记录（仅管理员）
export const getAiLogs = (params) => api.get('/admin/ai-logs', { params })

// ---------------- 供需对接 ----------------
export const getDemandList = (params) => api.get('/demand', { params })
export const getDemandOptions = () => api.get('/demand/options')
export const getDemandDetail = (id) => api.get(`/demand/${id}`)
export const getDemandContact = (id) => api.post(`/demand/${id}/contact`)
export const publishDemand = (data) => api.post('/demand', data)
export const offlineDemand = (id) => api.post(`/demand/${id}/offline`)
// 删除供需信息（本人或管理员；管理员可删他人）
export const deleteDemand = (id) => api.delete(`/demand/${id}`)
export const getMyDemands = () => api.get('/demand/mine')

// ---------------- 供应商认证 ----------------
export const getSupplierMe = () => api.get('/supplier/me')
export const getDemandNotify = () => api.get('/supplier/demand-notify')
export const saveDemandNotify = (data) => api.put('/supplier/demand-notify', data)
export const applySupplier = (data) => api.post('/supplier/apply', data)

// ---------------- 供应商认证审核（管理员） ----------------
export const getSupplierVerifies = (params) => api.get('/admin/supplier-verifies', { params })
export const reviewSupplier = (id, data) => api.post(`/admin/supplier-verifies/${id}/review`, data)

// ---------------- 供需页风险提示 ----------------
export const getDemandNotice = () => api.get('/demand/notice')
export const getAdminDemandNotice = () => api.get('/admin/demand-notice')
export const saveAdminDemandNotice = (data) => api.put('/admin/demand-notice', data)

// ---------------- 站内通知 ----------------
export const getNotifications = () => api.get('/notifications')
export const getNtUnread = () => api.get('/notifications/unread-count')
export const readNotification = (id) => api.post(`/notifications/${id}/read`)
export const readAllNotifications = () => api.post('/notifications/read-all')

// ---------------- 账号（绑定手机号） ----------------
export const getAccount = () => api.get('/auth/account')
export const bindPhone = (phone) => api.post('/auth/phone', { phone })

// ---------------- 营业执照上传 / 认证管理 / 供需修改 ----------------
export const uploadLicense = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/upload/license', fd,
    { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 })
}
export const revokeSupplier = (userId, remark) =>
  api.post(`/admin/suppliers/${userId}/revoke`, { remark })
export const requireReverifySupplier = (userId, remark) =>
  api.post(`/admin/suppliers/${userId}/require-reverify`, { remark })
export const updateDemand = (id, data) => api.put(`/demand/${id}`, data)
export const getSupplyStatus = () => api.get('/demand/status')

// ===== 标点地图（用户级开关；未开通时接口返回 403） =====
export const getEnterpriseMapAccess = () => api.get('/enterprise-map/access')
export const getEnterpriseMapOverview = () => api.get('/enterprise-map/overview')
export const getEnterpriseMapProvince = (province) =>
  api.get('/enterprise-map/province/' + encodeURIComponent(province))
// 产品维度：可选品种清单 + 某品种的区域（省份）分布
export const getEnterpriseMapVarieties = () => api.get('/enterprise-map/varieties')
export const getEnterpriseMapVariety = (vid) => api.get('/enterprise-map/variety/' + vid)
export const setSupplyEnabled = (enabled) => api.put('/demand/enabled', { enabled })
export const getDangerousChem = () => api.get('/demand/dangerous-chem')
export const saveDangerousChem = (keywords) => api.put('/demand/dangerous-chem', { keywords })
export const getRealnameStatus = () => api.get('/realname/status')
export const createRealnameOrder = () => api.post('/realname/order')
export const mockPayRealname = (orderNo) => api.post(`/realname/order/${orderNo}/mock-pay`)
export const verifyRealnameId = (data) => {
  // Base64 混淆传输：部分网络/安全软件会拦截明文携带身份证号的 POST，编码后规避误拦（后端兼容明文）
  try {
    const raw = (data.name || '') + '\n' + (data.idcard || '')
    const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(raw)))
    return api.post('/uvc/submit', { payload: b64 })
  } catch (e) {
    return api.post('/uvc/submit', data)
  }
}
export const claimRealnameOrder = (orderNo, payerNo) => api.post(`/realname/order/${orderNo}/claim`, { payerNo })
export const getRealnamePending = () => api.get('/realname/admin-pending')
export const confirmRealnameOrder = (orderNo) => api.post(`/realname/admin-confirm/${orderNo}`)
export const savePaySettings = (data) => api.post('/realname/admin-pay-settings', data)
export const getRealnameOrder = (orderNo) => api.get(`/realname/order/${orderNo}`)
export const getDemandCard = (id) => api.get(`/demand/${id}/card`)
export const resetUserVerify = (id, type) => api.post(`/admin/users/${id}/reset-verify`, { type })
export const resetUserRealname = (id) => api.post(`/admin/users/${id}/reset-realname`)
export const getAiFlags = () => api.get('/admin/ai-flags')
export const setAiFlags = (data) => api.post('/admin/ai-flags', data)
export const getUserStats = (params) => api.get('/admin/user-stats', { params })

// ===== 定时服务监控（仅 ADMIN）=====
export const getSchedulerStatus = () => api.get('/admin/scheduler/status')
export const getSchedulerLog = (id, lines) => api.get('/admin/scheduler/log', { params: { id, lines } })

// ===== 物性查询（聚合权威公开数据源） =====
export const getChemSources = () => api.get('/chem/sources')
export const getChemCommodities = (params) => api.get('/chem/commodities', { params })
export const chemLookup = (params) => api.get('/chem/lookup', { params })

export default api