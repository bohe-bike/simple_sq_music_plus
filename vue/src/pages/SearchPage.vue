<template>
  <section class="page-wrap">
    <h2 class="page-title">搜索与下载</h2>

    <div class="form-grid">
      <div class="form-col-2">
        <el-select v-model="searchForm.plugName" placeholder="数据来源" style="width: 100%">
          <el-option v-for="item in plugOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </div>
      <div class="form-col-2">
        <el-select v-model="searchForm.mode" placeholder="搜索类别" style="width: 100%">
          <el-option label="单曲" value="song" />
          <el-option label="专辑" value="album" />
          <el-option label="歌手" value="artist" />
        </el-select>
      </div>
      <div class="form-col-6">
        <el-input v-model="searchForm.keyword" placeholder="输入关键词，比如歌名 / 专辑 / 歌手" clearable @keyup.enter="search" />
      </div>
      <div class="form-col-2">
        <el-button type="primary" :loading="loading" style="width: 100%" @click="search">搜索</el-button>
      </div>
    </div>

    <el-divider />

    <el-table v-loading="loading" :data="rows" empty-text="暂无搜索结果" stripe>
      <el-table-column prop="name" label="名称" min-width="220" show-overflow-tooltip />
      <el-table-column prop="artistLabel" label="歌手" min-width="170" show-overflow-tooltip />
      <el-table-column prop="albumName" label="专辑" min-width="180" show-overflow-tooltip />
      <el-table-column prop="duration" label="时长" width="90" />
      <el-table-column label="可用音质" min-width="220">
        <template #default="scope">
          <el-space wrap>
            <el-tag v-for="bit in scope.row.brTypes || []" :key="bit.id" effect="plain" type="warning">{{ bit.id }}</el-tag>
            <span v-if="!scope.row.brTypes || !scope.row.brTypes.length" class="muted">无</span>
          </el-space>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="scope">
          <el-space>
            <el-button size="small" @click="play(scope.row)" :disabled="searchForm.mode !== 'song'">播放</el-button>
            <el-button size="small" type="primary" @click="download(scope.row)">下载</el-button>
          </el-space>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :current-page="searchForm.pageIndex"
        :page-size="searchForm.pageSize"
        :total="total"
        @current-change="onPageChange"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/modules'

interface PlugOption {
  value: string
  label: string
}

interface SearchRow {
  id: string
  name: string
  artistLabel?: string
  artistName?: string[]
  artistids?: string[]
  albumName?: string
  albumid?: string
  duration?: string
  plugName?: string
  brTypes?: Array<{ id: string }>
}

const loading = ref(false)
const total = ref(0)
const rows = ref<SearchRow[]>([])
const plugOptions = ref<PlugOption[]>([])

const searchForm = reactive({
  plugName: 'kw',
  mode: 'song',
  keyword: '',
  pageIndex: 1,
  pageSize: 20,
})

const fetchOptions = async () => {
  const res = await api.getOptions()
  plugOptions.value = (res.data || []) as PlugOption[]
  if (plugOptions.value.length && !plugOptions.value.find((p) => p.value === searchForm.plugName)) {
    searchForm.plugName = plugOptions.value[0].value
  }
}

const normalizeRows = (records: Array<Record<string, unknown>>) => {
  return records.map((item) => ({
    id: String(item.id || ''),
    name: String(item.name || item.albumName || item.artistName || ''),
    artistLabel: Array.isArray(item.artistName) ? item.artistName.join(' / ') : String(item.artistName || ''),
    artistName: (item.artistName || []) as string[],
    artistids: (item.artistids || []) as string[],
    albumName: String(item.albumName || ''),
    albumid: String(item.albumid || ''),
    duration: item.duration ? String(item.duration) : '-',
    plugName: String(item.plugName || searchForm.plugName),
    brTypes: (item.brTypes || []) as Array<{ id: string }>,
  }))
}

const search = async () => {
  if (!searchForm.keyword.trim()) {
    ElMessage.warning('请输入关键词')
    return
  }
  loading.value = true
  try {
    const params = {
      plugName: searchForm.plugName,
      keyword: searchForm.keyword.trim(),
      pageIndex: searchForm.pageIndex,
      pageSize: searchForm.pageSize,
    }

    const request =
      searchForm.mode === 'song'
        ? api.searchSong(params)
        : searchForm.mode === 'artist'
          ? api.searchArtist(params)
          : api.searchAlbum(params)

    const res = await request
    const data = (res.data || {}) as Record<string, unknown>
    const records = (data.records || []) as Array<Record<string, unknown>>
    rows.value = normalizeRows(records)
    total.value = Number(data.searchTotal || records.length || 0)
  } finally {
    loading.value = false
  }
}

const onPageChange = (page: number) => {
  searchForm.pageIndex = page
  search()
}

const play = async (row: SearchRow) => {
  if (!row.brTypes?.length) {
    ElMessage.warning('该歌曲暂无可播放音质')
    return
  }
  const payload = {
    id: row.id,
    plugName: row.plugName || searchForm.plugName,
    brType: row.brTypes[0],
    brTypes: row.brTypes,
  }
  const res = await api.getDownloadUrl(payload)
  const url = (res.data as Record<string, unknown>)?.url as string
  if (url) {
    window.open(url, '_blank')
  } else {
    ElMessage.info('已请求播放地址，请检查插件登录状态')
  }
}

const download = async (row: SearchRow) => {
  if (searchForm.mode === 'song') {
    await api.downloadSong({
      id: row.id,
      name: row.name,
      albumName: row.albumName,
      artistName: row.artistName || [],
      artistids: row.artistids || [],
      plugName: row.plugName || searchForm.plugName,
      brTypes: row.brTypes || [],
    })
    ElMessage.success('已加入下载队列')
    return
  }

  if (searchForm.mode === 'artist') {
    await api.downloadArtistAlbum({
      plugName: row.plugName || searchForm.plugName,
      artistid: row.id,
    })
    ElMessage.success('已提交歌手专辑下载任务')
    return
  }

  await api.downloadAlbum({
    plugName: row.plugName || searchForm.plugName,
    albumid: row.id,
    albumName: row.name,
    artistName: row.artistLabel,
  })
  ElMessage.success('已提交专辑下载任务')
}

onMounted(async () => {
  await fetchOptions()
})
</script>

<style scoped>
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.muted {
  color: var(--text-subtle);
  font-size: 12px;
}
</style>
