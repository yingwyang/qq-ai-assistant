<template>
  <router-view />
</template>

<script>
export default {
  name: 'App'
};
</script>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  line-height: 1.6;
  color: #333;
  background-color: #f5f5f5;
  margin: 0 !important;
  padding: 0 !important;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

#app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
}
</style>
