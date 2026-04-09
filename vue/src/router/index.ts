import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../stores/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../pages/LoginPage.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('../layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/search' },
        { path: '/search', name: 'search', component: () => import('../pages/SearchPage.vue') },
        { path: '/download', name: 'download', component: () => import('../pages/DownloadPage.vue') },
        { path: '/parse-text', name: 'parse-text', component: () => import('../pages/ParseTextPage.vue') },
        { path: '/parse-playlist', name: 'parse-playlist', component: () => import('../pages/ParsePlaylistPage.vue') },
        { path: '/monitor', name: 'monitor', component: () => import('../pages/MonitorPage.vue') },
        { path: '/settings', name: 'settings', component: () => import('../pages/SettingsPage.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.meta.public) {
    return true
  }
  if (!getToken()) {
    return '/login'
  }
  return true
})

export default router
