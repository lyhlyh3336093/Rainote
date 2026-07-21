import request from '@/utils/request'

// 查询笔记列表
export function listColumn(query) {
  return request({
    url: '/system/column/list',
    method: 'get',
    params: query
  })
}

// 查询笔记详细
export function getColumn(id) {
  return request({
    url: '/system/column/' + id,
    method: 'get'
  })
}

// 新增笔记
export function addColumn(data) {
  return request({
    url: '/system/column',
    method: 'post',
    data: data
  })
}

// 修改笔记
export function updateColumn(data) {
  return request({
    url: '/system/column',
    method: 'put',
    data: data
  })
}

// 删除笔记
export function delColumn(id) {
  return request({
    url: '/system/column/' + id,
    method: 'delete'
  })
}
