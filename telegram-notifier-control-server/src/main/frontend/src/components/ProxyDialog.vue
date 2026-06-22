<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑代理' : '新建代理'"
    width="480px"
    @update:model-value="$emit('update:modelValue', $event)"
    @opened="initForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="协议" prop="protocol">
        <el-select v-model="form.protocol" style="width: 100%">
          <el-option label="HTTP" value="HTTP" />
          <el-option label="HTTPS" value="HTTPS" />
          <el-option label="SOCKS5" value="SOCKS5" />
        </el-select>
      </el-form-item>
      <el-form-item label="主机" prop="host">
        <el-input v-model="form.host" />
      </el-form-item>
      <el-form-item label="端口" prop="port">
        <el-input-number v-model="form.port" :min="1" :max="65535" style="width: 100%" />
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="form.username" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input
          v-model="form.password"
          type="password"
          show-password
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
import { createProxy, updateProxy } from '@/api/proxies'
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
  protocol: 'SOCKS5',
  host: '',
  port: 1080,
  username: '',
  password: '',
  enabled: true,
})

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  protocol: [{ required: true, message: '请选择协议', trigger: 'change' }],
  host: [{ required: true, message: '请输入主机', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口', trigger: 'blur' }],
}

function initForm() {
  if (props.record) {
    Object.assign(form, {
      name: props.record.name || '',
      protocol: props.record.protocol || 'SOCKS5',
      host: props.record.host || '',
      port: props.record.port || 1080,
      username: props.record.username || '',
      password: '', // masked field: empty = don't change
      enabled: props.record.enabled ?? true,
    })
  } else {
    Object.assign(form, {
      name: '',
      protocol: 'SOCKS5',
      host: '',
      port: 1080,
      username: '',
      password: '',
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
    const data = { ...form }
    if (isEdit.value && !data.password) {
      delete data.password // don't send empty password on edit
    }
    if (isEdit.value) {
      await updateProxy(props.record.id, data)
      ElMessage.success('代理更新成功')
    } else {
      await createProxy(data)
      ElMessage.success('代理创建成功')
    }
    emit('saved')
  } catch (e) {
    handleApiError(e)
  } finally {
    saving.value = false
  }
}
</script>
