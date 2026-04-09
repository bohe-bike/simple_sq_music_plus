<template>
  <section class="page-wrap">
    <h2 class="page-title">解析文本</h2>
    <p class="hint">粘贴歌单文本后可先识别预览，再批量加入下载队列。</p>

    <el-input
      v-model="text"
      type="textarea"
      :rows="11"
      resize="vertical"
      placeholder="请输入待解析文本，每行建议包含 歌曲名 + 歌手"
    />

    <div class="actions">
      <el-button :loading="parsing" @click="preview">识别预览</el-button>
      <el-button type="primary" :loading="downloading" @click="downloadDirect">直接解析并下载</el-button>
      <el-button type="success" :disabled="!previewRows.length" @click="downloadPreviewRows">下载预览结果</el-button>
    </div>

    <el-divider />

    <el-table :data="previewRows" stripe empty-text="暂无识别结果">
      <el-table-column prop="musicName" label="歌曲" min-width="200" show-overflow-tooltip />
      <el-table-column prop="artistName" label="歌手" min-width="160" show-overflow-tooltip />
      <el-table-column prop="albumName" label="专辑" min-width="180" show-overflow-tooltip />
      <el-table-column prop="plugName" label="来源" width="100" />
      <el-table-column prop="reason" label="识别信息" min-width="220" show-overflow-tooltip />
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/modules'

const text = ref('')
const parsing = ref(false)
const downloading = ref(false)
const previewRows = ref<Array<Record<string, unknown>>>([])

const preview = async () => {
  if (!text.value.trim()) {
    ElMessage.warning('请输入需要解析的文本')
    return
  }
  parsing.value = true
  try {
    const res = await api.parserText({ text: text.value })
    previewRows.value = ((res.data || []) as Array<Record<string, unknown>>).map((item) => {
      const song = (item.plugSearchMusicResult || {}) as Record<string, unknown>
      return {
        source: item,
        musicName: song.name || item.musicName || '-',
        artistName: Array.isArray(song.artistName) ? song.artistName.join(' / ') : song.artistName || '-',
        albumName: song.albumName || '-',
        plugName: song.plugName || '-',
        reason: item.msg || item.reason || '识别完成',
      }
    })
    ElMessage.success(`识别完成，共 ${previewRows.value.length} 条`)
  } finally {
    parsing.value = false
  }
}

const downloadDirect = async () => {
  if (!text.value.trim()) {
    ElMessage.warning('请输入需要解析的文本')
    return
  }
  downloading.value = true
  try {
    const res = await api.downloadParserText({ text: text.value })
    ElMessage.success(res.msg || '已提交后台解析下载任务')
  } finally {
    downloading.value = false
  }
}

const downloadPreviewRows = async () => {
  const payload = previewRows.value.map((r) => r.source as Record<string, unknown>)
  await api.downloadParserTextResult(payload)
  ElMessage.success('预览结果已加入下载队列')
}
</script>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--text-subtle);
}

.actions {
  margin-top: 12px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
