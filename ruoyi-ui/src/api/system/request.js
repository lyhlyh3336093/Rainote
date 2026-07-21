import request from '@/utils/request'

// 查询friendrequest列表
export function listRequest(query) {
  return request({
    url: '/system/request/list',
    method: 'get',
    params: query
  })
}

// 查询friendrequest详细
export function getRequest(id) {
  return request({
    url: '/system/request/' + id,
    method: 'get'
  })
}

// 新增friendrequest
export function addRequest(data) {
  return request({
    url: '/system/request',
    method: 'post',
    data: data
  })
}

// 修改friendrequest
export function updateRequest(data) {
  return request({
    url: '/system/request',
    method: 'put',
    data: data
  })
}

// 删除friendrequest
export function delRequest(id) {
  return request({
    url: '/system/request/' + id,
    method: 'delete'
  })
}
