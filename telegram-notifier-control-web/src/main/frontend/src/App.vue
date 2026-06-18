<template>
  <main class="shell">
    <section v-if="loading" class="panel">Loading</section>

    <section v-else-if="needsAdminInit" class="panel auth-panel">
      <h1>Telegram Notifier</h1>
      <form @submit.prevent="initialize">
        <label>Username<input v-model="auth.username" autocomplete="username"></label>
        <label>Password<input v-model="auth.password" type="password" autocomplete="new-password"></label>
        <button type="submit">Initialize</button>
      </form>
    </section>

    <section v-else-if="!token" class="panel auth-panel">
      <h1>Telegram Notifier</h1>
      <form @submit.prevent="login">
        <label>Username<input v-model="auth.username" autocomplete="username"></label>
        <label>Password<input v-model="auth.password" type="password" autocomplete="current-password"></label>
        <button type="submit">Login</button>
      </form>
    </section>

    <template v-else>
      <aside class="sidebar">
        <strong>Telegram Notifier</strong>
        <button v-for="tab in tabs" :key="tab" :class="{ active: view === tab }" @click="view = tab">{{ tab }}</button>
        <button @click="logout">Logout</button>
      </aside>

      <section class="content">
        <header>
          <h1>{{ view }}</h1>
          <button @click="refresh">Refresh</button>
        </header>

        <section v-if="view === 'Accounts'" class="grid">
          <form class="panel" @submit.prevent="saveAccount">
            <label>Name<input v-model="account.displayName"></label>
            <label>Phone<input v-model="account.phoneNumber"></label>
            <label>Scan seconds<input v-model.number="account.scanFrequencySeconds" type="number"></label>
            <label>Unread age seconds<input v-model.number="account.unreadAgeThresholdSeconds" type="number"></label>
            <fieldset class="check-list">
              <legend>Proxies</legend>
              <label v-for="item in state.proxies" :key="item.id" class="check-row">
                <input v-model="account.proxyIds" type="checkbox" :value="item.id">
                <span>{{ item.name }} · {{ item.protocol }}://{{ item.host }}:{{ item.port }}</span>
              </label>
              <p v-if="state.proxies.length === 0" class="muted">No proxies configured</p>
            </fieldset>
            <button type="submit">Save Account</button>
          </form>
          <article v-for="item in state.accounts" :key="item.id" class="panel">
            <h2>{{ item.displayName }}</h2>
            <p>{{ item.authorizationState }} · active proxy {{ proxyName(item.activeProxyId) }}</p>
            <p v-if="item.connectionError" class="error">{{ item.connectionError }}</p>
            <fieldset class="check-list">
              <legend>Proxy chain</legend>
              <label v-for="proxyItem in state.proxies" :key="proxyItem.id" class="check-row">
                <input v-model="state.accountProxyIds[item.id]" type="checkbox" :value="proxyItem.id">
                <span>{{ proxyItem.name }} · {{ proxyItem.protocol }}://{{ proxyItem.host }}:{{ proxyItem.port }}</span>
              </label>
              <p v-if="state.proxies.length === 0" class="muted">No proxies configured</p>
            </fieldset>
            <div class="row">
              <button @click="startAccount(item.id)">Start</button>
              <button @click="stopAccount(item.id)">Stop</button>
              <button @click="saveAccountProxies(item.id)">Save Proxies</button>
              <button @click="submitLogin(item.id, 'phone')">Phone</button>
              <button @click="submitLogin(item.id, 'code')">Code</button>
              <button @click="submitLogin(item.id, 'password')">Password</button>
            </div>
          </article>
        </section>

        <section v-if="view === 'Proxies'" class="grid">
          <form class="panel" @submit.prevent="saveProxy">
            <label>Name<input v-model="proxy.name"></label>
            <label>Protocol<select v-model="proxy.protocol"><option>HTTP</option><option>HTTPS</option><option>SOCKS5</option></select></label>
            <label>Host<input v-model="proxy.host"></label>
            <label>Port<input v-model.number="proxy.port" type="number"></label>
            <label>Username<input v-model="proxy.username"></label>
            <label>Password<input v-model="proxy.password" type="password"></label>
            <button type="submit">Save Proxy</button>
          </form>
          <article v-for="item in state.proxies" :key="item.id" class="panel">
            <h2>{{ item.name }}</h2>
            <p>{{ item.protocol }}://{{ item.host }}:{{ item.port }}</p>
          </article>
        </section>

        <section v-if="view === 'Channels'" class="grid">
          <form class="panel" @submit.prevent="saveChannel">
            <label>Name<input v-model="channel.name"></label>
            <label>Server URL<input v-model="channel.config.serverUrl"></label>
            <label>Device Key<input v-model="channel.config.deviceKey"></label>
            <button type="submit">Save Bark Channel</button>
          </form>
          <article v-for="item in state.channels" :key="item.id" class="panel">
            <h2>{{ item.name }}</h2>
            <p>{{ item.type }} · {{ item.enabled ? 'enabled' : 'disabled' }}</p>
            <button @click="testChannel(item.id)">Test</button>
          </article>
        </section>

        <section v-if="view === 'Rules'" class="grid">
          <form class="panel" @submit.prevent="saveRule">
            <label>Name<input v-model="rule.name"></label>
            <label>Source label<input v-model="rule.sourceLabel"></label>
            <label>Template<input v-model="rule.template"></label>
            <label>Text contains<input v-model="rule.condition.value"></label>
            <label>Channel IDs<input v-model="rule.channelIdsText"></label>
            <button type="submit">Save Rule</button>
          </form>
          <article v-for="item in state.rules" :key="item.id" class="panel">
            <h2>{{ item.name }}</h2>
            <p>{{ item.template }}</p>
          </article>
        </section>

        <section v-if="view === 'Statistics'" class="panel">
          <pre>{{ JSON.stringify(state.statistics, null, 2) }}</pre>
        </section>

        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="notice" class="notice">{{ notice }}</p>
      </section>
    </template>
  </main>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ApiError, api, getToken, setToken } from './api.js'

const tabs = ['Accounts', 'Proxies', 'Channels', 'Rules', 'Statistics']
const loading = ref(true)
const needsAdminInit = ref(false)
const token = ref(getToken())
const view = ref('Accounts')
const error = ref('')
const notice = ref('')
const auth = reactive({ username: 'admin', password: '' })
const account = reactive({ displayName: '', phoneNumber: '', scanFrequencySeconds: 60, unreadAgeThresholdSeconds: 3600, proxyIds: [] })
const proxy = reactive({ name: '', protocol: 'SOCKS5', host: '', port: 1080, username: '', password: '', enabled: true })
const channel = reactive({ name: '', type: 'BARK', enabled: true, config: { serverUrl: 'https://api.day.app', deviceKey: '' } })
const rule = reactive({
  name: '',
  sourceLabel: '服务器',
  template: '{{receivedAt}} 收到来自{{sourceLabel}}的通知消息',
  condition: { field: 'text', op: 'contains', value: '' },
  channelIdsText: ''
})
const state = reactive({ accounts: [], proxies: [], accountProxyIds: {}, channels: [], rules: [], statistics: {} })

async function boot() {
  const status = await api('/system/bootstrap-status')
  needsAdminInit.value = status.needsAdminInit
  loading.value = false
  if (token.value && !needsAdminInit.value) await refresh()
}

async function initialize() {
  await api('/system/admin-init', { method: 'POST', body: JSON.stringify(auth) })
  needsAdminInit.value = false
}

async function login() {
  const response = await api('/auth/login', { method: 'POST', body: JSON.stringify(auth) })
  setToken(response.token)
  token.value = response.token
  await refresh()
}

function logout() {
  setToken(null)
  token.value = null
}

async function refresh() {
  error.value = ''
  notice.value = ''
  try {
    const [accounts, proxies, channels, rules, statistics] = await Promise.all([
      api('/accounts'),
      api('/proxies'),
      api('/channels'),
      api('/rules'),
      api('/statistics')
    ])
    state.accounts = accounts
    state.proxies = proxies
    state.channels = channels
    state.rules = rules
    state.statistics = statistics
    for (const item of state.accounts) {
      if (!state.accountProxyIds[item.id]) state.accountProxyIds[item.id] = []
    }
    await loadAccountProxyBindings()
  } catch (e) {
    handleApiError(e)
  }
}

async function saveAccount() {
  await runAction(async () => {
    const created = await api('/accounts', { method: 'POST', body: JSON.stringify({ ...account, enabled: true }) })
    await api(`/accounts/${created.id}/proxies`, { method: 'PUT', body: JSON.stringify({ proxyIds: account.proxyIds }) })
    resetAccountForm()
    await refresh()
    notice.value = `Account ${created.displayName} saved`
  })
}

async function startAccount(id) {
  await runAction(async () => {
    const status = await api(`/accounts/${id}/start`, { method: 'POST' })
    await refresh()
    notice.value = `Account ${id} status: ${status.authorizationState}, active proxy ${proxyName(status.activeProxyId)}`
  })
}

async function stopAccount(id) {
  await runAction(async () => {
    const status = await api(`/accounts/${id}/stop`, { method: 'POST' })
    await refresh()
    notice.value = `Account ${id} status: ${status.authorizationState}`
  })
}

async function saveAccountProxies(id) {
  await runAction(async () => {
    await api(`/accounts/${id}/proxies`, { method: 'PUT', body: JSON.stringify({ proxyIds: state.accountProxyIds[id] || [] }) })
    await refresh()
    notice.value = `Account ${id} proxies saved`
  })
}

async function submitLogin(id, step) {
  const value = window.prompt(`Submit ${step}`)
  if (!value) return
  await runAction(async () => {
    const status = await api(`/accounts/${id}/login/${step}`, { method: 'POST', body: JSON.stringify({ value }) })
    await refresh()
    notice.value = `Account ${id} status: ${status.authorizationState}`
  })
}

async function saveProxy() {
  await runAction(async () => {
    await api('/proxies', { method: 'POST', body: JSON.stringify(proxy) })
    await refresh()
  })
}

async function saveChannel() {
  await runAction(async () => {
    await api('/channels', { method: 'POST', body: JSON.stringify(channel) })
    await refresh()
  })
}

async function testChannel(id) {
  await api(`/channels/${id}/test`, { method: 'POST' })
}

async function saveRule() {
  const channelIds = rule.channelIdsText.split(',').map(value => value.trim()).filter(Boolean).map(Number)
  await runAction(async () => {
    await api('/rules', { method: 'POST', body: JSON.stringify({ ...rule, channelIds }) })
    await refresh()
  })
}

async function loadAccountProxyBindings() {
  const bindings = await Promise.all(state.accounts.map(async item => [item.id, await api(`/accounts/${item.id}/proxies`)]))
  const next = Object.fromEntries(bindings)
  for (const key of Object.keys(state.accountProxyIds)) {
    if (!Object.hasOwn(next, key)) delete state.accountProxyIds[key]
  }
  for (const [id, proxyIds] of bindings) {
    state.accountProxyIds[id] = proxyIds
  }
}

function proxyName(id) {
  if (!id) return 'none'
  return state.proxies.find(item => item.id === id)?.name || id
}

function resetAccountForm() {
  account.displayName = ''
  account.phoneNumber = ''
  account.scanFrequencySeconds = 60
  account.unreadAgeThresholdSeconds = 3600
  account.proxyIds = []
}

async function runAction(action) {
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (e) {
    handleApiError(e)
  }
}

function handleApiError(e) {
  if (e instanceof ApiError && e.status === 401) {
    setToken(null)
    token.value = null
    error.value = 'Session expired. Please log in again.'
    return
  }
  error.value = e.message
}

onMounted(boot)
</script>
