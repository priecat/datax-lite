import { createRouter, createWebHashHistory } from 'vue-router'
import Layout from './layout/Layout.vue'
import { getToken } from './auth'

const routes = [
  {
    path: '/login',
    component: () => import('./views/Login.vue'),
    meta: { title: '登录', noAuth: true }
  },
  {
    path: '/',
    component: Layout,
    redirect: '/tasks',
    children: [
      { path: 'datasources', component: () => import('./views/DataSource.vue'), meta: { title: '数据源管理' } },
      { path: 'dicts', component: () => import('./views/DictManage.vue'), meta: { title: '类型字典' } },
      { path: 'tasks', component: () => import('./views/TaskList.vue'), meta: { title: '数据传输' } },
      { path: 'tasks/new', component: () => import('./views/TaskEdit.vue'), meta: { title: '新建传输任务' } },
      { path: 'tasks/edit/:id', component: () => import('./views/TaskEdit.vue'), meta: { title: '编辑传输任务' } },
      { path: 'schedules', component: () => import('./views/Schedule.vue'), meta: { title: '定时任务' } },
      { path: 'logs', component: () => import('./views/Logs.vue'), meta: { title: '执行日志' } },
      { path: 'plugins', component: () => import('./views/Plugins.vue'), meta: { title: '插件管理' } },
      { path: 'users', component: () => import('./views/UserManage.vue'), meta: { title: '用户管理' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/tasks' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 全局前置守卫：除登录页外，未登录一律跳转登录页
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - DataX Lite` : 'DataX Lite'
  if (to.meta.noAuth) {
    next()
    return
  }
  if (!getToken()) {
    next('/login')
    return
  }
  next()
})

export default router
