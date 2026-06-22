import { api } from './index'

export function listRules() {
  return api('/rules')
}

export function getRule(id) {
  return api(`/rules/${id}`)
}

export function createRule(data) {
  return api('/rules', { method: 'POST', body: JSON.stringify(data) })
}

export function updateRule(id, data) {
  return api(`/rules/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

export function deleteRule(id) {
  return api(`/rules/${id}`, { method: 'DELETE' })
}
