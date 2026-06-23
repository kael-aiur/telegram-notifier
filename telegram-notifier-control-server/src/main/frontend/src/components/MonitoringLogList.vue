<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>监控日志</span>
        <el-button size="small" @click="fetchLogs">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </template>
    <el-table :data="logs" v-loading="loading" stripe size="small">
      <el-table-column label="扫描时间" min-width="180">
        <template #default="{ row }">{{ formatTime(row.scannedAt) }}</template>
      </el-table-column>
      <el-table-column prop="chatId" label="会话 ID" width="150" />
      <el-table-column prop="unreadCount" label="未读数" width="100" align="center" />
      <el-table-column prop="notifiedCount" label="推送数" width="100" align="center" />
    </el-table>
    <div class="pagination" v-if="logs.length > 0 || offset > 0">
      <el-button :disabled="offset === 0" @click="prevPage">上一页</el-button>
      <span class="page-info">第 {{ Math.floor(offset / pageSize) + 1 }} 页</span>
      <el-button :disabled="logs.length < pageSize" @click="nextPage">下一页</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { listMonitoringLogs } from '@/api/accounts'
import { handleApiError } from '@/utils/error'

const props = defineProps({
  accountId: {
    type: [String, Number],
    required: true,
  },
})

const logs = ref([])
const loading = ref(false)
const pageSize = 20
const offset = ref(0)

watch(() => props.accountId, () => { offset.value = 0; fetchLogs() })
onMounted(fetchLogs)

async function fetchLogs() {
  loading.value = true
  try {
    logs.value = await listMonitoringLogs(props.accountId, { limit: pageSize, offset: offset.value })
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
</style>
