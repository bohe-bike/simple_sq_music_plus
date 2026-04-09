import { defineStore } from 'pinia'

const TOKEN_KEY = 'sqmusic_token'
const USER_KEY = 'sqmusic_username'
const DEVICE_KEY = 'sqmusic_device'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    username: localStorage.getItem(USER_KEY) || '',
    device: localStorage.getItem(DEVICE_KEY) || 'pc',
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    setSession(token: string, username: string, device: string) {
      this.token = token
      this.username = username
      this.device = device
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, username)
      localStorage.setItem(DEVICE_KEY, device)
    },
    clearSession() {
      this.token = ''
      this.username = ''
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    },
  },
})

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ''
