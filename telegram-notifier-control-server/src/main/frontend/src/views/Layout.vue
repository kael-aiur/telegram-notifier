<template>
  <el-container class="layout-container">
    <el-aside width="220px" class="layout-aside">
      <div class="logo">Telegram Notifier</div>
      <el-menu
        :default-active="route.path"
        router
        class="aside-menu"
      >
        <el-menu-item index="/accounts">
          <el-icon><User /></el-icon>
          <span>账号管理</span>
        </el-menu-item>
        <el-menu-item index="/channels">
          <el-icon><Bell /></el-icon>
          <span>推送通道</span>
        </el-menu-item>
        <el-menu-item index="/proxies">
          <el-icon><Connection /></el-icon>
          <span>网络代理</span>
        </el-menu-item>
        <el-menu-item index="/rules">
          <el-icon><List /></el-icon>
          <span>推送规则</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <span class="header-title">{{ routeTitle }}</span>
        <el-button type="danger" text @click="handleLogout">退出登录</el-button>
      </el-header>
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Bell, Connection, List } from '@element-plus/icons-vue'
import { logout, setToken } from '@/api/auth'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()

const routeTitle = computed(() => {
  const titles = {
    '/accounts': '账号管理',
    '/channels': '推送通道',
    '/proxies': '网络代理',
    '/rules': '推送规则',
  }
  return titles[route.path] || 'Telegram Notifier'
})

async function handleLogout() {
  try {
    await logout()
  } catch {
    // ignore logout errors
  }
  setToken(null)
  router.push({ name: 'Login' })
  ElMessage.success('已退出登录')
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
}
.layout-aside {
  background: #304156;
  overflow: hidden;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
  font-weight: bold;
  background: #263445;
}
.aside-menu {
  border-right: none;
  background: #304156;
}
.aside-menu .el-menu-item {
  color: #bfcbd9;
}
.aside-menu .el-menu-item:hover,
.aside-menu .el-menu-item.is-active {
  background: #263445;
  color: #409eff;
}
.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e6e6e6;
  background: #fff;
}
.header-title {
  font-size: 18px;
  font-weight: 500;
}
.layout-main {
  background: #f5f7fa;
  overflow-y: auto;
}
</style>
