import { api } from './index'

export function listChannels() {
  return api('/channels')
}

export function getChannel(id) {
  return api(`/channels/${id}`)
}

export function createChannel(data) {
  return api('/channels', { method: 'POST', body: JSON.stringify(data) })
}

export function updateChannel(id, data) {
  return api(`/channels/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

export function deleteChannel(id) {
  return api(`/channels/${id}`, { method: 'DELETE' })
}

export function testChannel(id) {
  return api(`/channels/${id}/test`, { method: 'POST' })
}
