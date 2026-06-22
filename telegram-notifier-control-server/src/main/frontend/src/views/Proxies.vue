<template>
  <div>
    <div class="table-header">
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新建代理
      </el-button>
    </div>

    <el-table :data="proxies" v-loading="loading" stripe>
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="protocol" label="协议" width="100" />
      <el-table-column label="地址" min-width="180">
        <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
      </el-table-column>
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
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <ProxyDialog
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
import { listProxies, deleteProxy } from '@/api/proxies'
import { handleApiError } from '@/utils/error'
import ProxyDialog from '@/components/ProxyDialog.vue'

const proxies = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const editingRecord = ref(null)

onMounted(fetchData)

async function fetchData() {
  loading.value = true
  try {
    proxies.value = await listProxies()
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

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除代理「${row.name}」？`, '确认删除', { type: 'warning' })
    await deleteProxy(row.id)
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
