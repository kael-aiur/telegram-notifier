<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑规则' : '新建规则'"
    width="600px"
    @update:model-value="$emit('update:modelValue', $event)"
    @opened="initForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="来源标签">
        <el-input v-model="form.sourceLabel" placeholder="服务器" />
      </el-form-item>
      <el-form-item label="模板">
        <el-input
          v-model="form.template"
          type="textarea"
          :rows="2"
          placeholder="{{receivedAt}} 收到来自{{sourceLabel}}的通知消息"
        />
      </el-form-item>

      <el-divider>匹配条件</el-divider>

      <el-form-item label="字段">
        <el-select v-model="form.conditionField" style="width: 100%">
          <el-option
            v-for="f in conditionFields"
            :key="f.value"
            :label="f.label"
            :value="f.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="操作符">
        <el-select v-model="form.conditionOp" style="width: 100%">
          <el-option label="包含 (contains)" value="contains" />
          <el-option label="等于 (equals)" value="equals" />
          <el-option label="正则 (regex)" value="regex" />
          <el-option label="在列表中 (in)" value="in" />
        </el-select>
      </el-form-item>
      <el-form-item label="值">
        <el-input v-model="form.conditionValue" placeholder="匹配值" />
      </el-form-item>

      <el-divider>推送通道</el-divider>

      <el-form-item label="通道">
        <el-select
          v-model="form.channelIds"
          multiple
          placeholder="选择推送通道"
          style="width: 100%"
        >
          <el-option
            v-for="ch in channelOptions"
            :key="ch.id"
            :label="ch.name"
            :value="ch.id"
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
import { createRule, updateRule } from '@/api/rules'
import { listChannels } from '@/api/channels'
import { handleApiError } from '@/utils/error'

const props = defineProps({
  modelValue: Boolean,
  record: Object,
  accountId: {
    type: [String, Number],
    required: true,
  },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const isEdit = computed(() => !!props.record?.id)
const formRef = ref()
const saving = ref(false)
const channelOptions = ref([])

const conditionFields = [
  { label: 'accountId - 账号 ID', value: 'accountId' },
  { label: 'chatId - 聊天 ID', value: 'chatId' },
  { label: 'chatTitle - 聊天标题', value: 'chatTitle' },
  { label: 'chatType - 聊天类型', value: 'chatType' },
  { label: 'senderId - 发送者 ID', value: 'senderId' },
  { label: 'senderName - 发送者名称', value: 'senderName' },
  { label: 'senderUsername - 发送者用户名', value: 'senderUsername' },
  { label: 'messageId - 消息 ID', value: 'messageId' },
  { label: 'text - 消息文本', value: 'text' },
]

const form = reactive({
  name: '',
  enabled: true,
  sourceLabel: '服务器',
  template: '{{receivedAt}} 收到来自{{sourceLabel}}的通知消息',
  conditionField: 'text',
  conditionOp: 'contains',
  conditionValue: '',
  channelIds: [],
})

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

async function initForm() {
  // Load channels for selection
  try {
    channelOptions.value = await listChannels()
  } catch (e) {
    handleApiError(e)
  }

  if (props.record) {
    const condition = props.record.condition || {}
    // Extract single leaf condition
    let field = 'text', op = 'contains', value = ''
    if (condition.field) {
      field = condition.field
      op = condition.op || 'contains'
      value = condition.value || ''
    }

    Object.assign(form, {
      name: props.record.name || '',
      enabled: props.record.enabled ?? true,
      sourceLabel: props.record.sourceLabel || '服务器',
      template: props.record.template || '',
      conditionField: field,
      conditionOp: op,
      conditionValue: value,
      channelIds: props.record.channelIds || [],
    })
  } else {
    Object.assign(form, {
      name: '',
      enabled: true,
      sourceLabel: '服务器',
      template: '{{receivedAt}} 收到来自{{sourceLabel}}的通知消息',
      conditionField: 'text',
      conditionOp: 'contains',
      conditionValue: '',
      channelIds: [],
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
    const condition = {}
    if (form.conditionValue) {
      condition.field = form.conditionField
      condition.op = form.conditionOp
      condition.value = form.conditionValue
    }

    const data = {
      accountId: Number(props.accountId),
      name: form.name,
      enabled: form.enabled,
      sourceLabel: form.sourceLabel,
      template: form.template,
      condition,
      channelIds: form.channelIds,
    }

    if (isEdit.value) {
      await updateRule(props.accountId, props.record.id, data)
      ElMessage.success('规则更新成功')
    } else {
      await createRule(props.accountId, data)
      ElMessage.success('规则创建成功')
    }
    emit('saved')
  } catch (e) {
    handleApiError(e)
  } finally {
    saving.value = false
  }
}
</script>
