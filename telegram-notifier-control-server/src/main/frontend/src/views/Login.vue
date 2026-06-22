<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <h2>Telegram Notifier</h2>
      </template>

      <el-form v-if="needsInit" :model="form" label-width="auto" @submit.prevent="handleInit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">
            初始化管理员
          </el-button>
        </el-form-item>
      </el-form>

      <el-form v-else :model="form" label-width="auto" @submit.prevent="handleLogin">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" style="margin-top: 12px" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getBootstrapStatus, initializeAdmin, login, setToken } from '@/api/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const needsInit = ref(false)
const loading = ref(false)
const error = ref('')
const form = reactive({ username: 'admin', password: '' })

onMounted(async () => {
  try {
    const status = await getBootstrapStatus()
    needsInit.value = status.needsAdminInit
  } catch (e) {
    error.value = e.message
  }
})

async function handleInit() {
  loading.value = true
  error.value = ''
  try {
    await initializeAdmin(form.username, form.password)
    needsInit.value = false
    ElMessage.success('管理员初始化成功，请登录')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function handleLogin() {
  loading.value = true
  error.value = ''
  try {
    const res = await login(form.username, form.password)
    setToken(res.token)
    router.push('/')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: #f5f7fa;
}
.login-card {
  width: 420px;
}
.login-card h2 {
  text-align: center;
  margin: 0;
}
</style>
