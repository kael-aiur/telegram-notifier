<template>
  <div v-loading="loading">
    <div class="detail-header">
      <el-page-header @back="$router.push('/accounts')">
        <template #content>
          <span class="page-title">{{ account.displayName || '账号详情' }}</span>
        </template>
      </el-page-header>
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <!-- 账号监控 Tab -->
      <el-tab-pane label="账号监控" name="monitor">
        <el-card shadow="never" class="info-card">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="授权状态">
              <el-tag :type="authStateType(account.authorizationState)" size="small">
                {{ account.authorizationState }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="运行状态">
              <el-tag v-if="account.authorizationState === 'READY'"
                      :type="account.running ? 'success' : 'info'" size="small">
                {{ account.running ? '运行中' : '已停止' }}
              </el-tag>
              <el-tag v-else type="info" size="small">未就绪</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="激活代理">
              {{ account.activeProxyId || '-' }}
            </el-descriptions-item>
          </el-descriptions>

          <div class="action-bar" v-if="account.authorizationState === 'READY'">
            <el-button v-if="!account.running" type="success" @click="handleStart">
              <el-icon><VideoPlay /></el-icon>启动监听
            </el-button>
            <el-button v-else type="warning" @click="handleStop">
              <el-icon><VideoPause /></el-icon>停止监听
            </el-button>
          </div>
        </el-card>

        <MonitoringLogList :account-id="accountId" />
        <WorkerLogList :account-id="accountId" style="margin-top: 16px" />
      </el-tab-pane>

      <!-- 设置 Tab -->
      <el-tab-pane label="设置" name="settings">
        <el-card shadow="never" class="settings-section">
          <template #header><span>基本信息</span></template>
          <el-form :model="basicForm" label-width="100px" style="max-width: 500px">
            <el-form-item label="名称">
              <el-input v-model="basicForm.displayName" />
            </el-form-item>
            <el-form-item label="电话">
              <el-input v-model="basicForm.phoneNumber" disabled />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingBasic" @click="handleSaveBasic">保存</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="settings-section">
          <template #header><span>代理链</span></template>
          <el-transfer
            v-model="selectedProxyIds"
            :data="allProxies"
            :titles="['可用代理', '已绑定']"
            :props="{ key: 'id', label: 'name' }"
          />
          <div style="margin-top: 12px">
            <el-button type="primary" :loading="savingProxies" @click="handleSaveProxies">保存代理链</el-button>
          </div>
        </el-card>

        <el-card shadow="never" class="settings-section">
          <template #header><span>监听会话</span></template>
          <div v-for="(chatId, index) in chatIdList" :key="index" class="chat-id-row">
            <el-input-number v-model="chatIdList[index]" :min="1" :controls="false" style="width: 300px" />
            <el-button type="danger" link @click="chatIdList.splice(index, 1)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-button type="primary" link @click="chatIdList.push(0)" style="margin-top: 8px">
            <el-icon><Plus /></el-icon>添加会话
          </el-button>
          <div style="margin-top: 12px">
            <el-button type="primary" :loading="savingChatIds" @click="handleSaveChatIds">保存监听会话</el-button>
          </div>
        </el-card>

        <el-card shadow="never" class="settings-section">
          <template #header>
            <div class="card-header-with-action">
              <span>推送规则</span>
              <el-button type="primary" size="small" @click="openRuleDialog(null)">
                <el-icon><Plus /></el-icon>新建规则
              </el-button>
            </div>
          </template>
          <el-table :data="rules" stripe size="small">
            <el-table-column prop="name" label="名称" min-width="120" />
            <el-table-column label="启用" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sourceLabel" label="来源标签" width="120" />
            <el-table-column label="通道" min-width="100">
              <template #default="{ row }">{{ row.channelIds?.length || 0 }} 个</template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="openRuleDialog(row)">编辑</el-button>
                <el-button type="danger" link @click="handleDeleteRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 推送记录 Tab -->
      <el-tab-pane label="推送记录" name="notifications">
        <NotifiedMessageList :account-id="accountId" />
      </el-tab-pane>

      <!-- 账号操作 Tab -->
      <el-tab-pane label="账号操作" name="actions">
        <el-card shadow="never">
          <template #header><span>危险操作</span></template>
          <p>删除账号将同时删除该账号的所有推送规则、监控日志和推送记录，且操作不可撤销。</p>
          <el-button type="danger" @click="handleDelete">删除账号</el-button>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <RuleDialog
      v-model="ruleDialogVisible"
      :record="editingRule"
      :account-id="accountId"
      @saved="handleRuleSaved"
    />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VideoPlay, VideoPause, Plus, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAccount, updateAccount, deleteAccount, startAccount, stopAccount,
  getAccountProxies, bindAccountProxies, submitScanSettings
} from '@/api/accounts'
import { listRules, deleteRule } from '@/api/rules'
import { listProxies } from '@/api/proxies'
import { handleApiError } from '@/utils/error'
import RuleDialog from '@/components/RuleDialog.vue'
import MonitoringLogList from '@/components/MonitoringLogList.vue'
import NotifiedMessageList from '@/components/NotifiedMessageList.vue'
import WorkerLogList from '@/components/WorkerLogList.vue'

const route = useRoute()
const router = useRouter()

const accountId = ref(Number(route.params.id))
const account = ref({})
const loading = ref(false)
const activeTab = ref('monitor')

// Basic form
const basicForm = reactive({ displayName: '', phoneNumber: '' })
const savingBasic = ref(false)

// Proxies
const selectedProxyIds = ref([])
const allProxies = ref([])
const savingProxies = ref(false)

// Chat IDs
const chatIdList = ref([])
const savingChatIds = ref(false)

// Rules
const rules = ref([])
const ruleDialogVisible = ref(false)
const editingRule = ref(null)

// Load account data
watch(() => route.params.id, (newId) => {
  if (newId) {
    accountId.value = Number(newId)
    fetchAccount()
  }
})

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([fetchAccount(), fetchProxies()])
  } finally {
    loading.value = false
  }
})

async function fetchAccount() {
  try {
    const acc = await getAccount(accountId.value)
    account.value = acc
    basicForm.displayName = acc.displayName
    basicForm.phoneNumber = acc.phoneNumber
    chatIdList.value = [...(acc.monitoredChatIds || [])]
    // Load account-specific data
    await Promise.all([fetchRules(), fetchProxyBindings()])
  } catch (e) {
    handleApiError(e)
  }
}

async function fetchProxies() {
  try {
    allProxies.value = await listProxies()
  } catch (e) {
    handleApiError(e)
  }
}

async function fetchProxyBindings() {
  try {
    const ids = await getAccountProxies(accountId.value)
    selectedProxyIds.value = ids
  } catch (e) {
    handleApiError(e)
  }
}

async function fetchRules() {
  try {
    rules.value = await listRules(accountId.value)
  } catch (e) {
    handleApiError(e)
  }
}

// Actions
async function handleStart() {
  try {
    await startAccount(accountId.value)
    ElMessage.success('监听已启动')
    await fetchAccount()
  } catch (e) {
    handleApiError(e)
  }
}

async function handleStop() {
  try {
    await stopAccount(accountId.value)
    ElMessage.success('监听已停止')
    await fetchAccount()
  } catch (e) {
    handleApiError(e)
  }
}

async function handleSaveBasic() {
  savingBasic.value = true
  try {
    await updateAccount(accountId.value, {
      displayName: basicForm.displayName,
      phoneNumber: basicForm.phoneNumber,
      enabled: account.value.enabled,
      scanFrequencySeconds: account.value.scanFrequencySeconds,
      unreadAgeThresholdSeconds: account.value.unreadAgeThresholdSeconds,
    })
    ElMessage.success('基本信息已保存')
    await fetchAccount()
  } catch (e) {
    handleApiError(e)
  } finally {
    savingBasic.value = false
  }
}

async function handleSaveProxies() {
  savingProxies.value = true
  try {
    await bindAccountProxies(accountId.value, selectedProxyIds.value)
    ElMessage.success('代理链已保存')
  } catch (e) {
    handleApiError(e)
  } finally {
    savingProxies.value = false
  }
}

async function handleSaveChatIds() {
  savingChatIds.value = true
  try {
    const validIds = chatIdList.value.filter(id => id > 0)
    await submitScanSettings(accountId.value, {
      scanFrequencySeconds: account.value.scanFrequencySeconds,
      unreadAgeThresholdSeconds: account.value.unreadAgeThresholdSeconds,
      monitoredChatIds: validIds,
    })
    ElMessage.success('监听会话已保存')
    await fetchAccount()
  } catch (e) {
    handleApiError(e)
  } finally {
    savingChatIds.value = false
  }
}

function openRuleDialog(rule) {
  editingRule.value = rule ? { ...rule } : null
  ruleDialogVisible.value = true
}

async function handleDeleteRule(rule) {
  try {
    await ElMessageBox.confirm(`确定删除规则「${rule.name}」？`, '确认删除', { type: 'warning' })
    await deleteRule(accountId.value, rule.id)
    ElMessage.success('规则已删除')
    await fetchRules()
  } catch (e) {
    if (e === 'cancel') return
    handleApiError(e)
  }
}

function handleRuleSaved() {
  ruleDialogVisible.value = false
  fetchRules()
}

async function handleDelete() {
  try {
    await ElMessageBox.confirm(
      `确定删除账号「${account.value.displayName}」？此操作不可撤销。`,
      '确认删除',
      { type: 'error', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
    await deleteAccount(accountId.value)
    ElMessage.success('账号已删除')
    router.push('/accounts')
  } catch (e) {
    if (e === 'cancel') return
    handleApiError(e)
  }
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
</script>

<style scoped>
.detail-header {
  margin-bottom: 16px;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
}
.detail-tabs {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}
.info-card {
  margin-bottom: 16px;
}
.action-bar {
  margin-top: 16px;
}
.settings-section {
  margin-bottom: 16px;
}
.card-header-with-action {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.chat-id-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
