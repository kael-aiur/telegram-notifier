import { ApiError, clearToken } from '@/api/index'
import router from '@/router'

export function handleApiError(e) {
  if (e instanceof ApiError && e.status === 401) {
    clearToken()
    router.push({ name: 'Login' })
    return
  }
  throw e
}
