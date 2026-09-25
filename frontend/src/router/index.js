import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('../views/Home.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '看板' }
      },
      {
        path: 'commodities',
        name: 'CommodityList',
        component: () => import('../views/CommodityList.vue'),
        meta: { title: '商品中心' }
      },
      {
        path: 'price-table',
        name: 'PriceTable',
        component: () => import('../views/PriceTable.vue'),
        meta: { title: '数据表' }
      },
      {
        path: 'commodity/:id',
        name: 'CommodityDetail',
        component: () => import('../views/CommodityDetail.vue'),
        meta: { title: '商品详情' }
      },
      {
        path: 'movers',
        name: 'Movers',
        component: () => import('../views/Movers.vue'),
        meta: { title: '涨跌排行' }
      },
      {
        path: 'analysis',
        name: 'Analysis',
        component: () => import('../views/Analysis.vue'),
        meta: { title: '报价点分析' }
      },
      {
        path: 'user-manage',
        name: 'UserManage',
        component: () => import('../views/UserManage.vue'),
        meta: { title: '用户管理', requiresAdmin: true }
      },
      {
        path: 'user-analytics',
        name: 'UserAnalytics',
        component: () => import('../views/UserAnalytics.vue'),
        meta: { title: '用户看板', requiresAdmin: true }
      },
      {
        path: 'ai-logs',
        name: 'AiLogs',
        component: () => import('../views/AiLogs.vue'),
        meta: { title: 'AI 问答记录', requiresAdmin: true }
      },
      {
        path: 'scheduler',
        name: 'Scheduler',
        component: () => import('../views/Scheduler.vue'),
        meta: { title: '定时服务监控', requiresAdmin: true }
      },
      {
        path: 'scheduler',
        name: 'Scheduler',
        component: () => import('../views/Scheduler.vue'),
        meta: { title: '定时服务监控', requiresAdmin: true }
      },
      {
        path: 'push-settings',
        name: 'PushSettings',
        component: () => import('../views/PushSettings.vue'),
        meta: { title: '推送设置' }
      },
      {
        path: 'supply-demand',
        name: 'SupplyDemand',
        component: () => import('../views/SupplyDemand.vue'),
        meta: { title: '供需广场' }
      },
      {
        path: 'supplier-verify',
        name: 'SupplierVerify',
        component: () => import('../views/SupplierVerify.vue'),
        meta: { title: '供应商认证' }
      },
      {
        path: 'supplier-audit',
        name: 'SupplierAudit',
        component: () => import('../views/SupplierAudit.vue'),
        meta: { title: '供应商审核', requiresAdmin: true }
      },
      {
        path: 'help',
        name: 'Help',
        component: () => import('../views/Help.vue'),
        meta: { title: '使用手册' }
      },
      {
        path: 'tools',
        name: 'OfficeTools',
        component: () => import('../views/OfficeTools.vue'),
        meta: { title: '办公工具' }
      },
      {
        path: 'enterprise-map',
        name: 'EnterpriseMap',
        component: () => import('../views/EnterpriseMap.vue'),
        meta: { title: '标点地图' }
      },
      {
        path: 'chem-data',
        name: 'ChemData',
        component: () => import('../views/ChemData.vue'),
        meta: { title: '物性查询' }
      },
      {
        path: 'open-api',
        name: 'OpenApi',
        component: () => import('../views/OpenApi.vue'),
        meta: { title: '开放 API' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
import { getSupplyStatus } from '../api/index'

router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth !== false && !authStore.isAuthenticated) {
    // 记住目标页面，登录后跳回
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (to.path === '/login' && authStore.isAuthenticated) {
    // 已登录，如果有 redirect 参数就跳那里
    const redirect = to.query.redirect || '/'
    next(redirect)
    return
  }

  // 供需对接模块总开关：关闭时普通用户不可直达（管理员不受限）
  if (to.path === '/supply-demand') {
    try {
      const st = await getSupplyStatus()
      const d = st && st.data ? st.data : st
      if (!d.admin && (!d.enabled || !d.realname)) { next('/'); return }
    } catch (e) { /* 状态获取失败不拦截 */ }
  }

  // 管理员页面权限校验
  if (to.meta.requiresAdmin && authStore.user?.role !== 'ADMIN') {
    next('/')
    return
  }

  next()
})

export default router