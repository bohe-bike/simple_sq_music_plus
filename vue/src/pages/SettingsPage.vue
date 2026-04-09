<template>
  <section class="page-wrap">
    <div class="header">
      <h2 class="page-title">设置</h2>
      <el-space>
        <el-upload :show-file-list="false" :auto-upload="false" :on-change="onImportFile">
          <el-button>导入歌单数据</el-button>
        </el-upload>
        <el-button @click="load">刷新</el-button>
        <el-button type="danger" plain @click="logout">退出登录</el-button>
      </el-space>
    </div>

    <el-card shadow="never" class="token-card">
      <div class="token-head">登录信息</div>
      <div>username: {{ auth.username }}</div>
      <div class="token">token: {{ auth.token || '-' }}</div>
    </el-card>

    <el-table :data="configRows" v-loading="loading" row-key="configKey" stripe>
      <el-table-column prop="configName" label="配置项" min-width="220" show-overflow-tooltip />
      <el-table-column prop="configKey" label="键" min-width="230" show-overflow-tooltip />
      <el-table-column label="当前值" min-width="260">
        <template #default="scope">
          <component
            :is="inputComponent(scope.row.configType)"
            v-model="scope.row._editing"
            :rows="scope.row.configType === 'input' ? 2 : undefined"
            :placeholder="scope.row.configRemark || '请输入值'"
            style="width: 100%"
          >
            <el-option
              v-for="opt in parseOptions(scope.row.configOptions)"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </component>
        </template>
      </el-table-column>
      <el-table-column prop="configType" label="类型" width="100" />
      <el-table-column prop="configRemark" label="备注" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="scope">
          <el-button size="small" type="primary" :disabled="scope.row.configDisabled === 1" @click="save(scope.row)">保存</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { api } from '../api/modules'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const configRows = ref<Array<Record<string, any>>>([])

const parseOptions = (raw: string) => {
  if (!raw) return []
  try {
    return JSON.parse(raw)
  } catch {
    return []
  }
}

const inputComponent = (type: string) => {
  if (type === 'select') return 'el-select'
  if (type === 'boolean') return 'el-switch'
  if (type === 'password') return 'el-input'
  if (type === 'path') return 'el-input'
  if (type === 'number') return 'el-input-number'
  return 'el-input'
}

const normalizeValue = (row: Record<string, any>) => {
  if (row.configType === 'boolean') {
    return row.configValue === 'true'
  }
  if (row.configType === 'number') {
    return Number(row.configValue || 0)
  }
  return row.configValue || ''
}

const toPayloadValue = (row: Record<string, any>) => {
  if (row.configType === 'boolean') {
    return row._editing ? 'true' : 'false'
  }
  return String(row._editing ?? '')
}

const load = async () => {
  loading.value = true
  try {
    const res = await api.getConfigList()
    configRows.value = ((res.data || []) as Array<Record<string, any>>).map((row) => ({
      ...row,
      _editing: normalizeValue(row),
    }))
  } finally {
    loading.value = false
  }
}

const save = async (row: Record<string, any>) => {
  await api.updateConfig({
    configId: row.configId,
    configKey: row.configKey,
    configType: row.configType,
    configValue: toPayloadValue(row),
  })
  ElMessage.success('保存成功')
  load()
}

const onImportFile = async (file: any) => {
  const formData = new FormData()
  formData.append('file', file.raw)
  await api.importSongList(formData)
  ElMessage.success('导入请求已提交')
}

const logout = async () => {
  await api.logout()
  auth.clearSession()
  ElMessage.success('已退出登录')
  router.replace('/login')
}

load()
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 10px;
  flex-wrap: wrap;
}

.token-card {
  margin-bottom: 14px;
}

.token-head {
  font-weight: 700;
  margin-bottom: 8px;
}

.token {
  margin-top: 6px;
  color: var(--text-subtle);
  word-break: break-all;
}
</style>
