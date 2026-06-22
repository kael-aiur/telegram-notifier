import { api } from './index'

export { getToken, setToken } from './index'

export function getBootstrapStatus() {
  return api('/system/bootstrap-status')
}

export function initializeAdmin(username, password) {
  return api('/system/admin-init', { method: 'POST', body: JSON.stringify({ username, password }) })
}

export function login(username, password) {
  return api('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
}

export function logout() {
  return api('/auth/logout', { method: 'POST' })
}
