<template>
  <div class="login-page">
    <div class="login-card">
      <h1>SqMusic 控制台</h1>
      <p>更清晰的流程提示，更稳定的批量下载。</p>
      <el-form label-position="top" :model="form" @submit.prevent="onLogin">
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="请输入账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="form.device" style="width: 100%">
            <el-option label="PC" value="pc" />
            <el-option label="移动端" value="mobile" />
            <el-option label="Web" value="web" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="onLogin">登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { api } from '../api/modules'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: 'admin',
  device: auth.device || 'pc',
})

const onLogin = async () => {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const res = await api.login(form)
    const token = (res.data?.tokenValue || res.data?.token || '') as string
    if (!token) {
      ElMessage.error('登录成功但未返回 token')
      return
    }
    auth.setSession(token, form.username, form.device)
    ElMessage.success('登录成功')
    await router.replace('/search')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 16px;
}

.login-card {
  width: min(460px, 100%);
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(12px);
  border: 1px solid #d9e2ec;
  border-radius: 22px;
  box-shadow: 0 22px 40px rgba(15, 23, 42, 0.12);
  padding: 26px;
}

h1 {
  margin: 0;
  font-size: 28px;
}

p {
  margin: 10px 0 18px;
  color: var(--text-subtle);
}
</style>
