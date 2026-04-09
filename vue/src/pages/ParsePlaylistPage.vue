<template>
  <section class="page-wrap">
    <h2 class="page-title">解析歌单</h2>
    <p class="hint">支持主流平台歌单链接，解析后可直接创建下载任务。</p>

    <div class="form-grid">
      <div class="form-col-10">
        <el-input v-model="url" placeholder="请输入歌单或专辑 URL" clearable @keyup.enter="parseInfo" />
      </div>
      <div class="form-col-2">
        <el-button style="width: 100%" :loading="loadingInfo" @click="parseInfo">解析信息</el-button>
      </div>
    </div>

    <el-card v-if="info" class="info-card" shadow="never">
      <template #header>识别结果</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="名称">{{ info.targetName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ info.plugName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ info.type || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ info.targetCount || '-' }}</el-descriptions-item>
        <el-descriptions-item label="链接" :span="2">{{ info.targetUrl || url }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <div class="actions">
      <el-button type="primary" :loading="downloading" @click="downloadFromUrl">解析并下载</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/modules'

const url = ref('')
const loadingInfo = ref(false)
const downloading = ref(false)
const info = ref<Record<string, any> | null>(null)

const parseInfo = async () => {
  if (!url.value.trim()) {
    ElMessage.warning('请输入 URL')
    return
  }
  loadingInfo.value = true
  try {
    const res = await api.parserUrlInfo({ url: url.value.trim() })
    info.value = (res.data || {}) as Record<string, any>
    ElMessage.success('解析成功')
  } finally {
    loadingInfo.value = false
  }
}

const downloadFromUrl = async () => {
  if (!url.value.trim()) {
    ElMessage.warning('请输入 URL')
    return
  }
  downloading.value = true
  try {
    const res = await api.downloadParserUrl({ url: url.value.trim() })
    ElMessage.success(res.msg || '已提交下载任务')
  } finally {
    downloading.value = false
  }
}
</script>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--text-subtle);
}

.info-card {
  margin-top: 14px;
}

.actions {
  margin-top: 14px;
}
</style>
