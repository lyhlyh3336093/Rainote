<template>
  <div v-if="isLoaded">
    <GridLayout
      :layout="layout"
      :col-num="colNum"
      :row-height="rowHeight"
      :is-draggable="isDraggable"
      :is-resizable="isResizable"
      :is-mirrored="isMirrored"
      :vertical-compact="verticalCompact"
      :margin="margin"
      :use-css-transforms="useCssTransforms"
      v-bind="$attrs"
    >
      <GridItem
        v-for="item in layout"
        :key="item.i"
        :x="item.x"
        :y="item.y"
        :w="item.w"
        :h="item.h"
        :i="item.i"
        :min-w="item.minW"
        :min-h="item.minH"
        :max-w="item.maxW"
        :max-h="item.maxH"
        :static="item.static"
        :drag-allow-from="item.dragAllowFrom"
        :drag-ignore-from="item.dragIgnoreFrom"
        :resize-ignore-from="item.resizeIgnoreFrom"
        :preserve-aspect-ratio="item.preserveAspectRatio"
        :drag-option="item.dragOption"
        :resize-option="item.resizeOption"
      >
        <slot :item="item" :index="getItemIndex(item.i)">
          <div class="grid-item-content">
            {{ item.i }}
          </div>
        </slot>
      </GridItem>
    </GridLayout>
  </div>
  <div v-else class="loading-placeholder">
    <a-spin size="large" />
    <p>正在加载网格布局组件...</p>
  </div>
</template>

<script setup lang="tsx">
import { ref, computed } from 'vue'

interface LayoutItem {
  i: string
  x: number
  y: number
  w: number
  h: number
  minW?: number
  minH?: number
  maxW?: number
  maxH?: number
  static?: boolean
  dragAllowFrom?: string
  dragIgnoreFrom?: string
  resizeIgnoreFrom?: string
  preserveAspectRatio?: boolean
  dragOption?: any
  resizeOption?: any
}

const props = defineProps<{
  layout: LayoutItem[]
  colNum?: number
  rowHeight?: number
  isDraggable?: boolean
  isResizable?: boolean
  isMirrored?: boolean
  verticalCompact?: boolean
  margin?: [number, number]
  useCssTransforms?: boolean
}>()

const emit = defineEmits<{
  'layout-updated': [layout: LayoutItem[]]
  'layout-created': [layout: LayoutItem[]]
  'layout-before-mount': [layout: LayoutItem[]]
  'layout-mounted': [layout: LayoutItem[]]
  'layout-ready': [layout: LayoutItem[]]
  'layout-update': [layout: LayoutItem[]]
  'move': [item: LayoutItem, newX: number, newY: number]
  'resize': [item: LayoutItem, newW: number, newH: number]
  'resized': [item: LayoutItem, newW: number, newH: number]
  'moved': [item: LayoutItem, newX: number, newY: number]
}>()

const isLoaded = ref(true)

const getItemIndex = (itemId: string) => {
  return props.layout.findIndex(item => item.i === itemId)
}

// 计算属性以便在模板中使用
const layout = computed(() => props.layout)
const colNum = computed(() => props.colNum || 12)
const rowHeight = computed(() => props.rowHeight || 30)
const isDraggable = computed(() => props.isDraggable !== false)
const isResizable = computed(() => props.isResizable !== false)
const isMirrored = computed(() => props.isMirrored || false)
const verticalCompact = computed(() => props.verticalCompact !== false)
const margin = computed(() => props.margin || [10, 10])
const useCssTransforms = computed(() => props.useCssTransforms !== false)

</script>

<style lang="scss" scoped>
.loading-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: #666;
  
  p {
    margin-top: 16px;
    font-size: 14px;
  }
}

.grid-item-content {
  width: 100%;
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
}
</style>
