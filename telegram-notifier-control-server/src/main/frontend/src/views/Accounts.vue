<template>
  <div>
    <div class="table-header">
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建账号
      </el-button>
    </div>

    <el-table :data="accounts" v-loading="loading" stripe>
      <el-table-column prop="displayName" label="名称" min-width="120" />
      <el-table-column prop="phoneNumber" label="电话" width="160" />
      <el-table-column label="授权状态" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="authStateType(row.authorizationState)" size="small">
            {{ row.authorizationState }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="运行状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.authorizationState === 'READY'" :type="row.running ? 'success' : 'info'" size="small">
            {{ row.running ? '运行中' : '已停止' }}
          </el-tag>
          <el-tag v-else type="info" size="small">未就绪</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="激活代理" width="120">
        <template #default="{ row }">{{ proxyName(row.activeProxyId) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <!-- 未就绪：只显示登录 -->
          <template v-if="row.authorizationState !== 'READY'">
            <el-button type="success" link @click="openLogin(row)">登录</el-button>
          </template>
          <!-- 已就绪：显示启动/停止、重新登录 -->
          <template v-else>
            <el-button v-if="!row.running" type="warning" link @click="handleStart(row)">启动</el-button>
            <el-button v-else type="info" link @click="handleStop(row)">停止</el-button>
            <el-button link @click="openLogin(row)">重新登录</el-button>
          </template>
          <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <AccountDialog
      v-model="accountDialogVisible"
      :record="editingRecord"
      @saved="handleSaved"
    />

    <LoginWizard
      v-model="loginWizardVisible"
      :account-id="loginAccountId"
      @completed="handleLoginCompleted"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAccounts, deleteAccount, startAccount, stopAccount } from '@/api/accounts'
import { listProxies } from '@/api/proxies'
import { handleApiError } from '@/utils/error'
import AccountDialog from '@/components/AccountDialog.vue'
import LoginWizard from '@/components/LoginWizard.vue'

const accounts = ref([])
const proxies = ref([])
const loading = ref(false)
const accountDialogVisible = ref(false)
const editingRecord = ref(null)
const loginWizardVisible = ref(false)
const loginAccountId = ref(null)

onMounted(fetchData)

async function fetchData() {
  loading.value = true
  try {
    const [acc, prox] = await Promise.all([listAccounts(), listProxies()])
    accounts.value = acc
    proxies.value = prox
  } catch (e) {
    handleApiError(e)
  } finally {
    loading.value = false
  }
}

function proxyName(id) {
  if (!id) return '-'
  return proxies.value.find(p => p.id === id)?.name || id
}

function authStateType(state) {
  const types = {
    READY: 'success',
    WAIT_PHONE: 'warning',
    WAIT_CODE: 'warning',
    WAIT_PASSWORD: 'warning',
    ERROR: 'danger',
  }
  return types[state] || 'info'
}

function openCreate() {
  editingRecord.value = null
  accountDialogVisible.value = true
}

function openEdit(row) {
  editingRecord.value = { ...row }
  accountDialogVisible.value = true
}

function openLogin(row) {
  loginAccountId.value = row.id
  loginWizardVisible.value = true
}

async function handleStart(row) {
  try {
    const status = await startAccount(row.id)
    ElMessage.success(`账号已启动，状态: ${status.authorizationState}`)
    await fetchData()
  } catch (e) {
    handleApiError(e)
  }
}

async function handleStop(row) {
  try {
    const status = await stopAccount(row.id)
    ElMessage.success(`账号已停止，状态: ${status.authorizationState}`)
    await fetchData()
  } catch (e) {
    handleApiError(e)
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除账号「${row.displayName}」？`, '确认删除', { type: 'warning' })
    await deleteAccount(row.id)
    ElMessage.success('删除成功')
    await fetchData()
  } catch (e) {
    if (e === 'cancel') return
    handleApiError(e)
  }
}

function handleSaved() {
  accountDialogVisible.value = false
  fetchData()
}

function handleLoginCompleted() {
  loginWizardVisible.value = false
  fetchData()
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<style scoped>
.table-header {
  margin-bottom: 16px;
}
</style>
