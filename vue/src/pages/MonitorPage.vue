<template>
  <section class="page-wrap">
    <div class="header">
      <h2 class="page-title">监听下载</h2>
      <el-button type="primary" @click="openAddDialog">新增监听</el-button>
    </div>

    <el-table :data="list" v-loading="loading" stripe empty-text="暂无监听任务">
      <el-table-column prop="targetName" label="名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="plugName" label="来源" width="100" />
      <el-table-column prop="type" label="类型" width="110" />
      <el-table-column prop="targetCount" label="数量" width="90" />
      <el-table-column prop="targetUrl" label="链接" min-width="230" show-overflow-tooltip />
      <el-table-column prop="targetDesc" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column prop="updateTime" label="更新时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="scope">
          <el-button text type="danger" @click="remove(scope.row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新增监听" width="680px">
      <div class="form-grid">
        <div class="form-col-9">
          <el-input v-model="form.targetUrl" placeholder="输入歌单/专辑 URL" />
        </div>
        <div class="form-col-3">
          <el-button :loading="parsing" style="width: 100%" @click="parseByUrl">从 URL 识别</el-button>
        </div>
        <div class="form-col-3">
          <el-input v-model="form.plugName" placeholder="来源 plugName" />
        </div>
        <div class="form-col-3">
          <el-input v-model="form.type" placeholder="类型" />
        </div>
        <div class="form-col-3">
          <el-input v-model="form.targetId" placeholder="目标 ID" />
        </div>
        <div class="form-col-3">
          <el-input v-model="form.targetCount" placeholder="数量" />
        </div>
        <div class="form-col-6">
          <el-input v-model="form.targetName" placeholder="名称" />
        </div>
        <div class="form-col-6">
          <el-input v-model="form.targetCover" placeholder="封面 URL" />
        </div>
        <div class="form-col-12">
          <el-input v-model="form.targetDesc" type="textarea" :rows="3" placeholder="描述" />
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存监听</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/modules'

const loading = ref(false)
const parsing = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const list = ref<Array<Record<string, any>>>([])

const form = reactive<Record<string, any>>({
  plugName: '',
  type: '',
  targetId: '',
  targetName: '',
  targetUrl: '',
  targetCount: 0,
  targetDesc: '',
  targetCover: '',
  enabled: 'true',
})

const fetchList = async () => {
  loading.value = true
  try {
    const res = await api.monitorList()
    list.value = (res.data || []) as Array<Record<string, any>>
  } finally {
    loading.value = false
  }
}

const openAddDialog = () => {
  dialogVisible.value = true
}

const parseByUrl = async () => {
  if (!form.targetUrl?.trim()) {
    ElMessage.warning('请先输入 URL')
    return
  }
  parsing.value = true
  try {
    const res = await api.parserUrlInfo({ url: form.targetUrl.trim() })
    Object.assign(form, (res.data || {}) as Record<string, any>)
    form.targetUrl = form.targetUrl || ''
    ElMessage.success('已自动填充识别信息')
  } finally {
    parsing.value = false
  }
}

const save = async () => {
  if (!form.plugName || !form.targetId) {
    ElMessage.warning('请至少填写来源和目标 ID')
    return
  }
  saving.value = true
  try {
    await api.monitorAdd({ ...form })
    ElMessage.success('监听已创建')
    dialogVisible.value = false
    fetchList()
  } finally {
    saving.value = false
  }
}

const remove = async (id: number) => {
  await api.monitorDelete({ id })
  ElMessage.success('已删除')
  fetchList()
}

fetchList()
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
</style>
