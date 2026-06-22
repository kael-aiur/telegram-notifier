import { api } from './index'

export function listAccounts() {
  return api('/accounts')
}

export function getAccount(id) {
  return api(`/accounts/${id}`)
}

export function createAccount(data) {
  return api('/accounts', { method: 'POST', body: JSON.stringify(data) })
}

export function updateAccount(id, data) {
  return api(`/accounts/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

export function deleteAccount(id) {
  return api(`/accounts/${id}`, { method: 'DELETE' })
}

export function startAccount(id) {
  return api(`/accounts/${id}/start`, { method: 'POST' })
}

export function stopAccount(id) {
  return api(`/accounts/${id}/stop`, { method: 'POST' })
}

export function getAccountStatus(id) {
  return api(`/accounts/${id}/status`)
}

export function submitPhone(id, value) {
  return api(`/accounts/${id}/login/phone`, { method: 'POST', body: JSON.stringify({ value }) })
}

export function submitCode(id, value) {
  return api(`/accounts/${id}/login/code`, { method: 'POST', body: JSON.stringify({ value }) })
}

export function submitPassword(id, value) {
  return api(`/accounts/${id}/login/password`, { method: 'POST', body: JSON.stringify({ value }) })
}

export function getAccountProxies(accountId) {
  return api(`/accounts/${accountId}/proxies`)
}

export function bindAccountProxies(accountId, proxyIds) {
  return api(`/accounts/${accountId}/proxies`, { method: 'PUT', body: JSON.stringify({ proxyIds }) })
}
