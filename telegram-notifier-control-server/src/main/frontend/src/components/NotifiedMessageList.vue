<template>
  <el-card shadow="never">
    <template #header><span>推送记录</span></template>
    <el-table :data="messages" v-loading="loading" stripe size="small">
      <el-table-column label="推送时间" width="180">
        <template #default="{ row }">{{ formatTime(row.notifiedAt) }}</template>
      </el-table-column>
      <el-table-column prop="chatId" label="会话 ID" width="150" />
      <el-table-column prop="messageId" label="消息 ID" width="120" />
      <el-table-column label="命中规则" min-width="150">
        <template #default="{ row }">
          <el-tag v-for="rule in row.matchedRules" :key="rule.id" size="small" class="rule-tag">
            {{ rule.name }}
          </el-tag>
          <span v-if="!row.matchedRules?.length">-</span>
        </template>
      </el-table-column>
      <el-table-column label="投递结果" min-width="200">
        <template #default="{ row }">
          <div v-for="(dr, idx) in row.deliveryResults" :key="idx" class="delivery-item">
            <el-tag :type="dr.success ? 'success' : 'danger'" size="small">
              通道 {{ dr.channelId }}: {{ dr.success ? '成功' : '失败' }}
            </el-tag>
            <span v-if="!dr.success && dr.message" class="error-msg">{{ dr.message }}</span>
          </div>
          <span v-if="!row.deliveryResults?.length">-</span>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination" v-if="messages.length > 0 || offset > 0">
      <el-button :disabled="offset === 0" @click="prevPage">上一页</el-button>
      <span class="page-info">第 {{ Math.floor(offset / pageSize) + 1 }} 页</span>
      <el-button :disabled="messages.length < pageSize" @click="nextPage">下一页</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { listNotifiedMessages } from '@/api/accounts'
import { handleApiError } from '@/utils/error'

const props = defineProps({
  accountId: {
    type: [String, Number],
    required: true,
  },
})

const messages = ref([])
const loading = ref(false)
const pageSize = 20
const offset = ref(0)

watch(() => props.accountId, () => { offset.value = 0; fetchMessages() })
onMounted(fetchMessages)

async function fetchMessages() {
  loading.value = true
  try {
    messages.value = await listNotifiedMessages(props.accountId, { limit: pageSize, offset: offset.value })
  } catch (e) {
    handleApiError(e)
  } finally {
    loading.value = false
  }
}

function prevPage() {
  offset.value = Math.max(0, offset.value - pageSize)
  fetchMessages()
}

function nextPage() {
  offset.value += pageSize
  fetchMessages()
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 16px;
}
.page-info {
  color: #666;
  font-size: 14px;
}
.rule-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.delivery-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: 8px;
  margin-bottom: 2px;
}
.error-msg {
  color: #f56c6c;
  font-size: 12px;
}
</style>
