<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="admin-shell">
    <nav class="admin-subnav" aria-label="Admin navigation">
      <RouterLink class="admin-subnav-item" to="/admin/users">Users</RouterLink>
      <RouterLink class="admin-subnav-item" to="/admin/roles">Roles</RouterLink>
    </nav>
    <RouterView />
  </div>
</template>
