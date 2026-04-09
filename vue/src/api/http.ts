import axios, { type AxiosError, type AxiosInstance, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { getToken } from '../stores/auth'

export interface ApiResponse<T = unknown> {
  code: number
  msg: string
  data: T
}

const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 20000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.satoken = token
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const payload = response.data
    if (payload.code === 200) {
      return payload
    }
    ElMessage.error(payload.msg || '请求失败')
    return Promise.reject(payload)
  },
  (error: AxiosError) => {
    const status = error.response?.status
    if (status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      localStorage.removeItem('sqmusic_token')
      window.location.hash = '#/login'
    } else {
      ElMessage.error(error.message || '网络异常')
    }
    return Promise.reject(error)
  },
)

export default http
