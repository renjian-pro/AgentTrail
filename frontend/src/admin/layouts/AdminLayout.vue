<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="admin-shell">
    <nav class="admin-subnav" aria-label="Admin navigation">
      <RouterLink class="admin-subnav-item" to="/admin/users">Users</RouterLink>
      <RouterLink class="admin-subnav-item" to="/admin/roles">Roles</RouterLink>
      <RouterLink class="admin-subnav-item" to="/admin/evaluation">Evaluation</RouterLink>
      <RouterLink class="admin-subnav-item" to="/admin/evaluation/cases">Golden Cases</RouterLink>
      <RouterLink class="admin-subnav-item" to="/admin/evaluation/candidates">Candidates</RouterLink>
    </nav>
    <RouterView />
  </div>
</template>
