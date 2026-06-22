<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑通道' : '新建通道'"
    width="480px"
    @update:model-value="$emit('update:modelValue', $event)"
    @opened="initForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="类型" prop="type">
        <el-select v-model="form.type" style="width: 100%">
          <el-option label="Bark" value="BARK" />
        </el-select>
      </el-form-item>
      <el-form-item label="服务地址">
        <el-input v-model="form.serverUrl" placeholder="https://api.day.app" />
      </el-form-item>
      <el-form-item label="Device Key" prop="deviceKey">
        <el-input
          v-model="form.deviceKey"
          :placeholder="isEdit ? '留空表示不修改' : ''"
        />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
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
import { createChannel, updateChannel } from '@/api/channels'
import { handleApiError } from '@/utils/error'

const props = defineProps({
  modelValue: Boolean,
  record: Object,
})
const emit = defineEmits(['update:modelValue', 'saved'])

const isEdit = computed(() => !!props.record?.id)
const formRef = ref()
const saving = ref(false)
const form = reactive({
  name: '',
  type: 'BARK',
  serverUrl: 'https://api.day.app',
  deviceKey: '',
  enabled: true,
})

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  deviceKey: [{
    required: !isEdit.value,
    message: '请输入 Device Key',
    trigger: 'blur',
    validator: (rule, value, callback) => {
      if (!isEdit.value && !value) {
        callback(new Error('请输入 Device Key'))
      } else {
        callback()
      }
    }
  }],
}

function initForm() {
  if (props.record) {
    const config = props.record.config || {}
    Object.assign(form, {
      name: props.record.name || '',
      type: props.record.type || 'BARK',
      serverUrl: config.serverUrl || 'https://api.day.app',
      deviceKey: '', // masked field: empty = don't change
      enabled: props.record.enabled ?? true,
    })
  } else {
    Object.assign(form, {
      name: '',
      type: 'BARK',
      serverUrl: 'https://api.day.app',
      deviceKey: '',
      enabled: true,
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
    const config = { serverUrl: form.serverUrl }
    if (form.deviceKey) {
      config.deviceKey = form.deviceKey
    }
    const data = {
      name: form.name,
      type: form.type,
      enabled: form.enabled,
      config,
    }
    if (isEdit.value) {
      await updateChannel(props.record.id, data)
      ElMessage.success('通道更新成功')
    } else {
      await createChannel(data)
      ElMessage.success('通道创建成功')
    }
    emit('saved')
  } catch (e) {
    handleApiError(e)
  } finally {
    saving.value = false
  }
}
</script>
