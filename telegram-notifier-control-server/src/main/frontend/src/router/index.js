import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '@/api/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
  },
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    children: [
      { path: '', redirect: '/accounts' },
      { path: 'accounts', name: 'Accounts', component: () => import('@/views/Accounts.vue') },
      { path: 'channels', name: 'Channels', component: () => import('@/views/Channels.vue') },
      { path: 'proxies', name: 'Proxies', component: () => import('@/views/Proxies.vue') },
      { path: 'rules', name: 'Rules', component: () => import('@/views/Rules.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to) => {
  if (to.name !== 'Login' && !getToken()) {
    return { name: 'Login' }
  }
})

export default router
