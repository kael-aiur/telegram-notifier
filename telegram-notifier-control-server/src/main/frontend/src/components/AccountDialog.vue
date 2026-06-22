<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑账号' : '新建账号'"
    width="520px"
    @update:model-value="$emit('update:modelValue', $event)"
    @opened="initForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="名称" prop="displayName">
        <el-input v-model="form.displayName" />
      </el-form-item>
      <el-form-item label="电话">
        <el-input v-model="form.phoneNumber" placeholder="可选" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="扫描间隔(秒)">
        <el-input-number v-model="form.scanFrequencySeconds" :min="1" style="width: 100%" />
      </el-form-item>
      <el-form-item label="未读阈值(秒)">
        <el-input-number v-model="form.unreadAgeThresholdSeconds" :min="1" style="width: 100%" />
      </el-form-item>

      <el-divider>代理链</el-divider>

      <el-form-item label="代理">
        <el-select
          v-model="form.proxyIds"
          multiple
          placeholder="选择代理（按优先级顺序）"
          style="width: 100%"
        >
          <el-option
            v-for="p in proxyOptions"
            :key="p.id"
            :label="`${p.name} · ${p.protocol}://${p.host}:${p.port}`"
            :value="p.id"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { createAccount, updateAccount, bindAccountProxies, getAccountProxies } from '@/api/accounts'
import { listProxies } from '@/api/proxies'
import { handleApiError } from '@/utils/error'

const props = defineProps({
  modelValue: Boolean,
  record: Object,
})
const emit = defineEmits(['update:modelValue', 'saved'])

const isEdit = computed(() => !!props.record?.id)
const formRef = ref()
const saving = ref(false)
const proxyOptions = ref([])

const form = reactive({
  displayName: '',
  phoneNumber: '',
  enabled: true,
  scanFrequencySeconds: 60,
  unreadAgeThresholdSeconds: 3600,
  proxyIds: [],
})

const rules = {
  displayName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

async function initForm() {
  // Load proxies for selection
  try {
    proxyOptions.value = await listProxies()
  } catch (e) {
    handleApiError(e)
  }

  if (props.record) {
    // Load current proxy bindings
    let proxyIds = []
    try {
      proxyIds = await getAccountProxies(props.record.id)
    } catch {
      // ignore
    }

    Object.assign(form, {
      displayName: props.record.displayName || '',
      phoneNumber: props.record.phoneNumber || '',
      enabled: props.record.enabled ?? true,
      scanFrequencySeconds: props.record.scanFrequencySeconds || 60,
      unreadAgeThresholdSeconds: props.record.unreadAgeThresholdSeconds || 3600,
      proxyIds,
    })
  } else {
    Object.assign(form, {
      displayName: '',
      phoneNumber: '',
      enabled: true,
      scanFrequencySeconds: 60,
      unreadAgeThresholdSeconds: 3600,
      proxyIds: [],
    })
  }
}

async function handleSave() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const data = {
      displayName: form.displayName,
      phoneNumber: form.phoneNumber,
      enabled: form.enabled,
      scanFrequencySeconds: form.scanFrequencySeconds,
      unreadAgeThresholdSeconds: form.unreadAgeThresholdSeconds,
    }

    let accountId
    if (isEdit.value) {
      await updateAccount(props.record.id, data)
      accountId = props.record.id
      ElMessage.success('账号更新成功')
    } else {
      const created = await createAccount(data)
      accountId = created.id
      ElMessage.success('账号创建成功')
    }

    // Bind proxies
    await bindAccountProxies(accountId, form.proxyIds)

    emit('saved')
  } catch (e) {
    handleApiError(e)
  } finally {
    saving.value = false
  }
}
</script>
