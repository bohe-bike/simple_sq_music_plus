<template>
  <section class="page-wrap">
    <h2 class="page-title">下载任务</h2>

    <div class="form-grid">
      <div class="form-col-2">
        <el-input v-model="filters.downloadMusicname" placeholder="歌曲名" clearable />
      </div>
      <div class="form-col-2">
        <el-input v-model="filters.downloadArtistname" placeholder="歌手名" clearable />
      </div>
      <div class="form-col-2">
        <el-input v-model="filters.downloadAlbumname" placeholder="专辑名" clearable />
      </div>
      <div class="form-col-2">
        <el-select v-model="filters.downloadPlugName" clearable placeholder="数据来源" style="width: 100%">
          <el-option label="kw" value="kw" />
          <el-option label="kg" value="kg" />
          <el-option label="netease" value="netease" />
          <el-option label="qqvip" value="qqvip" />
          <el-option label="apple" value="apple" />
        </el-select>
      </div>
      <div class="form-col-2">
        <el-select v-model="filters.downloadStatus" clearable placeholder="下载状态" style="width: 100%">
          <el-option label="等待下载" value="waiting" />
          <el-option label="下载中" value="loading" />
          <el-option label="下载成功" value="success" />
          <el-option label="下载失败" value="error" />
        </el-select>
      </div>
      <div class="form-col-2">
        <el-date-picker
          v-model="downloadTime"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          range-separator="到"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 100%"
        />
      </div>
      <div class="form-col-12 actions">
        <el-button type="primary" :loading="loading" @click="fetchTasks">搜索</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-divider direction="vertical" />
        <el-button @click="batchAction('againError')">重试失败任务</el-button>
        <el-button @click="batchAction('refreshLoading')">重置下载中任务</el-button>
        <el-button type="danger" plain @click="batchAction('delError')">清空失败</el-button>
        <el-button type="danger" plain @click="batchAction('delSuccess')">清空成功</el-button>
        <el-button type="danger" plain @click="batchAction('delWaiting')">清空等待</el-button>
      </div>
    </div>

    <el-divider />

    <el-table :data="records" v-loading="loading" stripe>
      <el-table-column prop="downloadMusicname" label="音乐名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="downloadArtistname" label="歌手" min-width="140" show-overflow-tooltip />
      <el-table-column prop="downloadAlbumname" label="专辑" min-width="160" show-overflow-tooltip />
      <el-table-column prop="downloadPlugName" label="来源" width="110" />
      <el-table-column prop="downloadTime" label="下载时间" width="180" />
      <el-table-column prop="downloadUpdateTime" label="更新时间" width="180" />
      <el-table-column prop="downloadBrType" label="下载类型" width="140" />
      <el-table-column label="状态" width="120">
        <template #default="scope">
          <el-tag :type="statusTag(scope.row.downloadStatus)">{{ statusLabel(scope.row.downloadStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="downloadMsg" label="消息" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="scope">
          <el-button text type="primary" @click="retryTask(scope.row.id)">重试</el-button>
          <el-button text type="danger" @click="deleteTask(scope.row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        background
        layout="sizes, prev, pager, next, total"
        :current-page="page.pageIndex"
        :page-size="page.pageSize"
        :page-sizes="[10, 20, 50]"
        :total="page.total"
        @size-change="onSizeChange"
        @current-change="onPageChange"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/modules'

const loading = ref(false)
const records = ref<Array<Record<string, any>>>([])
const downloadTime = ref<[string, string] | []>([])

const filters = reactive({
  downloadMusicname: '',
  downloadArtistname: '',
  downloadAlbumname: '',
  downloadPlugName: '',
  downloadStatus: '',
})

const page = reactive({
  pageIndex: 1,
  pageSize: 20,
  total: 0,
})

const statusLabel = (status: string) => {
  const map: Record<string, string> = {
    waiting: '等待下载',
    loading: '下载中',
    success: '下载成功',
    error: '下载失败',
  }
  return map[status] || status || '-'
}

const statusTag = (status: string) => {
  if (status === 'success') return 'success'
  if (status === 'error') return 'danger'
  if (status === 'loading') return 'warning'
  return 'info'
}

const fetchTasks = async () => {
  loading.value = true
  try {
    const payload = {
      ...filters,
      pageIndex: page.pageIndex,
      pageSize: page.pageSize,
      downloadTimeStart: downloadTime.value?.[0] || null,
      downloadTimeEnd: downloadTime.value?.[1] || null,
    }
    const res = await api.taskList(payload)
    const data = (res.data || {}) as Record<string, any>
    records.value = data.records || []
    page.total = Number(data.total || 0)
  } finally {
    loading.value = false
  }
}

const resetFilters = () => {
  filters.downloadMusicname = ''
  filters.downloadArtistname = ''
  filters.downloadAlbumname = ''
  filters.downloadPlugName = ''
  filters.downloadStatus = ''
  downloadTime.value = []
  page.pageIndex = 1
  fetchTasks()
}

const deleteTask = async (id: number) => {
  await ElMessageBox.confirm('删除后不可恢复，确认继续？', '删除任务', { type: 'warning' })
  await api.taskDelete({ id })
  ElMessage.success('删除成功')
  fetchTasks()
}

const retryTask = async (id: number) => {
  await api.taskRefreshOne({ id })
  ElMessage.success('任务已重置为等待下载')
  fetchTasks()
}

const batchAction = async (type: string) => {
  if (type === 'againError') await api.taskAgainError()
  if (type === 'refreshLoading') await api.taskRefreshLoading()
  if (type === 'delError') await api.taskDeleteError()
  if (type === 'delSuccess') await api.taskDeleteSuccess()
  if (type === 'delWaiting') await api.taskDeleteWaiting()
  ElMessage.success('操作成功')
  fetchTasks()
}

const onPageChange = (p: number) => {
  page.pageIndex = p
  fetchTasks()
}

const onSizeChange = (size: number) => {
  page.pageSize = size
  page.pageIndex = 1
  fetchTasks()
}

fetchTasks()
</script>

<style scoped>
.actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
</style>
