<template>
  <el-container class="app-shell">
    <el-header class="shell-header">
      <div class="brand-wrap">
        <div class="brand-logo">SqMusic</div>
        <div class="speed">上传 {{ uploadSpeed }} | 下载 {{ downloadSpeed }}</div>
      </div>
      <el-menu
        mode="horizontal"
        :default-active="activePath"
        @select="onSelect"
        class="top-menu"
      >
        <el-menu-item index="/search">搜索</el-menu-item>
        <el-menu-item index="/download">下载</el-menu-item>
        <el-menu-item index="/parse-text">解析文本</el-menu-item>
        <el-menu-item index="/parse-playlist">解析歌单</el-menu-item>
        <el-menu-item index="/monitor">监听下载</el-menu-item>
      </el-menu>
      <el-button text class="settings-entry" @click="router.push('/settings')">设置</el-button>
    </el-header>

    <el-main class="shell-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/modules'

const route = useRoute()
const router = useRouter()
const activePath = computed(() => route.path)

const uploadSpeed = ref('0.00 B/s')
const downloadSpeed = ref('0.00 B/s')
let timer: number | undefined

const fetchSpeed = async () => {
  try {
    const res = await api.getNetwork()
    const report = (res.data as Record<string, unknown>) || {}
    uploadSpeed.value = String(report.uploadSpeedFormatted || '0.00 B/s')
    downloadSpeed.value = String(report.downloadSpeedFormatted || '0.00 B/s')
  } catch {
    // ignore polling errors
  }
}

const onSelect = (path: string) => {
  router.push(path)
}

onMounted(() => {
  fetchSpeed()
  timer = window.setInterval(fetchSpeed, 2000)
})

onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.shell-header {
  display: grid;
  grid-template-columns: 1fr auto auto;
  align-items: center;
  gap: 18px;
  height: 74px;
  padding: 0 20px;
  background: linear-gradient(90deg, #f8fbff 0%, #ffffff 100%);
  border-bottom: 1px solid var(--line);
}

.brand-wrap {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand-logo {
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

.speed {
  color: var(--text-subtle);
  font-weight: 600;
  font-size: 13px;
}

.top-menu {
  border-bottom: none;
  background: transparent;
}

.settings-entry {
  justify-self: end;
  color: var(--text-main);
  font-weight: 600;
}

.shell-main {
  padding: 18px;
}

@media (max-width: 980px) {
  .shell-header {
    grid-template-columns: 1fr;
    height: auto;
    padding: 12px;
    gap: 8px;
  }

  .brand-logo {
    font-size: 28px;
  }

  .top-menu {
    overflow-x: auto;
  }

  .shell-main {
    padding: 10px;
  }
}
</style>
