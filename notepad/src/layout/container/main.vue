<template>
  <section class="app-main flex flex-col">
    <Breadcrumb/>
    <router-view v-slot="{ Component, route }">
      <transition appear mode="out-in" name="fade-transform">
        <!--           <keep-alive>-->
        <component :is="Component" :key="route.name"/>
        <!--           </keep-alive>-->
      </transition>
    </router-view>
<!--    <ToDoList/>-->
  </section>
</template>
<script setup>
import Breadcrumb from "../breadcrumb/index.vue";
// import ToDoList from '../../components/todo/index.vue'
</script>
