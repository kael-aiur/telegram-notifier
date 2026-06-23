import { api } from './index'

export function listRules(accountId) {
  return api(`/accounts/${accountId}/rules`)
}

export function getRule(accountId, id) {
  return api(`/accounts/${accountId}/rules/${id}`)
}

export function createRule(accountId, data) {
  return api(`/accounts/${accountId}/rules`, { method: 'POST', body: JSON.stringify(data) })
}

export function updateRule(accountId, id, data) {
  return api(`/accounts/${accountId}/rules/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

export function deleteRule(accountId, id) {
  return api(`/accounts/${accountId}/rules/${id}`, { method: 'DELETE' })
}
