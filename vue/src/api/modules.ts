import http, { type ApiResponse } from './http'

export interface SearchParams {
  plugName: string
  keyword: string
  pageIndex: number
  pageSize: number
}

export const api = {
  login: (payload: { username: string; password: string; device: string }) =>
    http.post<unknown, ApiResponse<{ tokenValue?: string; token?: string }>>('/api/config/login', payload),
  isLogin: () => http.get<unknown, ApiResponse<boolean>>('/api/config/isLogin'),
  logout: () => http.post('/api/config/logout'),
  version: () => http.get<unknown, ApiResponse<string>>('/api/config/version'),
  getNetwork: () => http.get('/api/config/getCurrentNetwork'),
  getOptions: () => http.get('/api/config/getOption'),
  getConfigList: () => http.get('/api/config/getConfigList'),
  updateConfig: (payload: Record<string, unknown>) => http.post('/api/config/updateConfig', payload),
  importSongList: (formData: FormData) => http.post('/api/config/importSongList', formData),

  searchSong: (params: SearchParams) => http.get('/api/music/searchSong', { params }),
  searchArtist: (params: SearchParams) => http.get('/api/music/searchArtist', { params }),
  searchAlbum: (params: SearchParams) => http.get('/api/music/searchAlbum', { params }),
  getDownloadUrl: (payload: Record<string, unknown>) => http.post('/api/music/getDownloadUrl', payload),

  downloadSong: (payload: Record<string, unknown>) => http.post('/api/download/downloadSong', payload),
  downloadArtistAlbum: (payload: Record<string, unknown>) => http.post('/api/download/downloadArtistAlbum', payload),
  downloadAlbum: (payload: Record<string, unknown>) => http.post('/api/download/downloadAlbum', payload),
  downloadParserText: (payload: { text: string }) => http.post('/api/download/downloadParserText', payload),
  downloadParserTextResult: (payload: unknown[]) => http.post('/api/download/downloadParserTextResult', payload),
  downloadParserUrl: (payload: { url: string }) => http.post('/api/download/downloadParserUrl', payload),

  parserText: (payload: { text: string }) => http.post('/api/parser/parserText', payload),
  parserUrlInfo: (payload: { url: string }) => http.post('/api/parser/parserUrlInfo', payload),

  taskList: (payload: Record<string, unknown>) => http.post('/api/task/list', payload),
  taskDelete: (payload: { id: number }) => http.post('/api/task/del', payload),
  taskRefreshOne: (payload: { id: number }) => http.post('/api/task/refreshTask', payload),
  taskAgainError: () => http.get('/api/task/againTask'),
  taskRefreshLoading: () => http.get('/api/task/refreshTask'),
  taskDeleteError: () => http.get('/api/task/delErrorTask'),
  taskDeleteSuccess: () => http.get('/api/task/delSuccessTask'),
  taskDeleteWaiting: () => http.get('/api/task/delWaitingTask'),

  monitorList: () => http.get('/api/monitor/list'),
  monitorAdd: (payload: Record<string, unknown>) => http.post('/api/monitor/add', payload),
  monitorDelete: (payload: { id: number }) => http.post('/api/monitor/delete', payload),
}
