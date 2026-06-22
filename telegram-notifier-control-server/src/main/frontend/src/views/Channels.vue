<template>
  <div>
    <div class="table-header">
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建通道
      </el-button>
    </div>

    <el-table :data="channels" v-loading="loading" stripe>
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="type" label="类型" width="100" />
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="success" link @click="handleTest(row)">测试</el-button>
          <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <ChannelDialog
      v-model="dialogVisible"
      :record="editingRecord"
      @saved="handleSaved"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listChannels, deleteChannel, testChannel } from '@/api/channels'
import { handleApiError } from '@/utils/error'
import ChannelDialog from '@/components/ChannelDialog.vue'

const channels = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const editingRecord = ref(null)

onMounted(fetchData)

async function fetchData() {
  loading.value = true
  try {
    channels.value = await listChannels()
  } catch (e) {
    handleApiError(e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingRecord.value = null
  dialogVisible.value = true
}

function openEdit(row) {
  editingRecord.value = { ...row }
  dialogVisible.value = true
}

async function handleTest(row) {
  try {
    const result = await testChannel(row.id)
    if (result.success) {
      ElMessage.success('测试发送成功')
    } else {
      ElMessage.error(`测试失败: ${result.message}`)
    }
  } catch (e) {
    handleApiError(e)
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除通道「${row.name}」？`, '确认删除', { type: 'warning' })
    await deleteChannel(row.id)
    ElMessage.success('删除成功')
    await fetchData()
  } catch (e) {
    if (e === 'cancel') return
    if (e.status === 409) {
      ElMessage.error(e.message)
    } else {
      handleApiError(e)
    }
  }
}

function handleSaved() {
  dialogVisible.value = false
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
