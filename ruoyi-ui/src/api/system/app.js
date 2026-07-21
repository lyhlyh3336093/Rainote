import request from '@/utils/request'

// 查询应用列表
export function listApp(query) {
  return request({
    url: '/system/app/list',
    method: 'get',
    params: query
  })
}

// 查询应用详细
export function getApp(id) {
  return request({
    url: '/system/app/' + id,
    method: 'get'
  })
}

// 新增应用
export function addApp(data) {
  return request({
    url: '/system/app',
    method: 'post',
    data: data
  })
}

// 修改应用
export function updateApp(data) {
  return request({
    url: '/system/app',
    method: 'put',
    data: data
  })
}

// 删除应用
export function delApp(id) {
  return request({
    url: '/system/app/' + id,
    method: 'delete'
  })
}
