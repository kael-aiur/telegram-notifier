import { api } from './index'

export function listProxies() {
  return api('/proxies')
}

export function getProxy(id) {
  return api(`/proxies/${id}`)
}

export function createProxy(data) {
  return api('/proxies', { method: 'POST', body: JSON.stringify(data) })
}

export function updateProxy(id, data) {
  return api(`/proxies/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

export function deleteProxy(id) {
  return api(`/proxies/${id}`, { method: 'DELETE' })
}
