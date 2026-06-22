<template>
  <el-dialog
    :model-value="modelValue"
    title="账号登录"
    width="480px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @opened="init"
  >
    <el-steps :active="currentStep" finish-status="success" align-center style="margin-bottom: 24px">
      <el-step title="手机号" />
      <el-step title="验证码" />
      <el-step title="两步密码" />
      <el-step title="完成" />
    </el-steps>

    <div v-if="loading" style="text-align: center">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <p>正在获取状态...</p>
    </div>

    <template v-else>
      <!-- WAIT_PHONE -->
      <div v-if="authState === 'WAIT_PHONE'">
        <el-form @submit.prevent="handlePhone">
          <el-form-item label="手机号">
            <el-input v-model="phoneInput" placeholder="+86..." autofocus />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="handlePhone" style="width: 100%">
              发送验证码
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- WAIT_CODE -->
      <div v-else-if="authState === 'WAIT_CODE'">
        <el-form @submit.prevent="handleCode">
          <el-form-item label="验证码">
            <el-input v-model="codeInput" placeholder="输入验证码" autofocus />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="handleCode" style="width: 100%">
              提交验证码
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- WAIT_PASSWORD -->
      <div v-else-if="authState === 'WAIT_PASSWORD'">
        <el-form @submit.prevent="handlePassword">
          <el-form-item label="两步验证密码">
            <el-input v-model="passwordInput" type="password" show-password placeholder="输入两步验证密码" autofocus />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="handlePassword" style="width: 100%">
              提交密码
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- READY -->
      <div v-else-if="authState === 'READY'" style="text-align: center">
        <el-result icon="success" title="登录成功" sub-title="账号已就绪" />
      </div>

      <!-- ERROR -->
      <div v-else-if="authState === 'ERROR'">
        <el-alert :title="errorMessage || '登录失败'" type="error" show-icon :closable="false" style="margin-bottom: 16px" />
        <el-button type="warning" @click="retry" style="width: 100%">重试</el-button>
      </div>

      <!-- Initial/Unknown -->
      <div v-else style="text-align: center">
        <p>当前状态: {{ authState || '未知' }}</p>
        <el-button type="primary" :loading="loading" @click="startLogin">开始登录</el-button>
      </div>
    </template>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" style="margin-top: 12px" />

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getAccountStatus, startAccount, submitPhone as apiSubmitPhone, submitCode as apiSubmitCode, submitPassword as apiSubmitPassword } from '@/api/accounts'
import { handleApiError } from '@/utils/error'

const props = defineProps({
  modelValue: Boolean,
  accountId: Number,
})
const emit = defineEmits(['update:modelValue', 'completed'])

const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const authState = ref('')
const errorMessage = ref('')
const activeProxyId = ref(null)

const phoneInput = ref('')
const codeInput = ref('')
const passwordInput = ref('')

const stepMap = {
  WAIT_PHONE: 0,
  WAIT_CODE: 1,
  WAIT_PASSWORD: 2,
  READY: 3,
  ERROR: 0,
}

const currentStep = computed(() => stepMap[authState.value] ?? 0)

async function init() {
  if (!props.accountId) return
  loading.value = true
  error.value = ''
  try {
    const status = await getAccountStatus(props.accountId)
    applyStatus(status)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function startLogin() {
  if (!props.accountId) return
  loading.value = true
  error.value = ''
  try {
    const status = await startAccount(props.accountId)
    applyStatus(status)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function applyStatus(status) {
  authState.value = status.authorizationState
  errorMessage.value = status.errorMessage || ''
  activeProxyId.value = status.activeProxyId

  if (status.authorizationState === 'READY') {
    ElMessage.success('登录成功')
    setTimeout(() => emit('completed'), 1000)
  }
}

async function handlePhone() {
  if (!phoneInput.value) return
  submitting.value = true
  error.value = ''
  try {
    const status = await apiSubmitPhone(props.accountId, phoneInput.value)
    applyStatus(status)
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

async function handleCode() {
  if (!codeInput.value) return
  submitting.value = true
  error.value = ''
  try {
    const status = await apiSubmitCode(props.accountId, codeInput.value)
    applyStatus(status)
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

async function handlePassword() {
  if (!passwordInput.value) return
  submitting.value = true
  error.value = ''
  try {
    const status = await apiSubmitPassword(props.accountId, passwordInput.value)
    applyStatus(status)
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

async function retry() {
  // Clear inputs and start login flow
  phoneInput.value = ''
  codeInput.value = ''
  passwordInput.value = ''
  await startLogin()
}
</script>
