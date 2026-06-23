<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>运行日志</span>
        <el-button size="small" @click="fetchLogs">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </template>
    <div class="log-container" v-loading="loading">
      <div v-if="logs.length === 0 && offset === 0" class="empty-text">暂无日志</div>
      <div v-for="log in logs" :key="log.id" class="log-entry">
        <span class="log-time">{{ formatTime(log.createdAt) }}</span>
        <span class="log-message">{{ log.message }}</span>
      </div>
    </div>
    <div class="pagination" v-if="logs.length > 0 || offset > 0">
      <el-button :disabled="offset === 0" @click="prevPage" size="small">上一页</el-button>
      <span class="page-info">第 {{ Math.floor(offset / pageSize) + 1 }} 页</span>
      <el-button :disabled="logs.length < pageSize" @click="nextPage" size="small">下一页</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { listWorkerLogs } from '@/api/accounts'
import { handleApiError } from '@/utils/error'

const props = defineProps({
  accountId: {
    type: [String, Number],
    required: true,
  },
})

const logs = ref([])
const loading = ref(false)
const pageSize = 100
const offset = ref(0)

watch(() => props.accountId, () => { offset.value = 0; fetchLogs() })
onMounted(fetchLogs)

async function fetchLogs() {
  loading.value = true
  try {
    logs.value = await listWorkerLogs(props.accountId, { limit: pageSize, offset: offset.value })
  } catch (e) {
    handleApiError(e)
  } finally {
    loading.value = false
  }
}

function prevPage() {
  offset.value = Math.max(0, offset.value - pageSize)
  fetchLogs()
}

function nextPage() {
  offset.value += pageSize
  fetchLogs()
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.log-container {
  max-height: 500px;
  overflow-y: auto;
  font-family: 'Courier New', Courier, monospace;
  font-size: 13px;
  line-height: 1.6;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 4px;
}
.log-entry {
  white-space: pre-wrap;
  word-break: break-all;
}
.log-time {
  color: #6a9955;
  margin-right: 8px;
}
.log-message {
  color: #d4d4d4;
}
.empty-text {
  color: #808080;
  text-align: center;
  padding: 20px;
}
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 12px;
}
.page-info {
  color: #666;
  font-size: 14px;
}
</style>
