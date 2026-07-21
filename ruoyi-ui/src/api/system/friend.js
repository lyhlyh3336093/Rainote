import request from '@/utils/request'

// 查询friend列表
export function listFriend(query) {
  return request({
    url: '/system/friend/list',
    method: 'get',
    params: query
  })
}

// 查询friend详细
export function getFriend(id) {
  return request({
    url: '/system/friend/' + id,
    method: 'get'
  })
}

// 新增friend
export function addFriend(data) {
  return request({
    url: '/system/friend',
    method: 'post',
    data: data
  })
}

// 修改friend
export function updateFriend(data) {
  return request({
    url: '/system/friend',
    method: 'put',
    data: data
  })
}

// 删除friend
export function delFriend(id) {
  return request({
    url: '/system/friend/' + id,
    method: 'delete'
  })
}
