import request from '@/utils/request'

// 查询笔记链接列表
export function listNotelink(query) {
  return request({
    url: '/system/notelink/list',
    method: 'get',
    params: query
  })
}

// 查询笔记链接详细
export function getNotelink(id) {
  return request({
    url: '/system/notelink/' + id,
    method: 'get'
  })
}

// U5: 按 noteId 查询引用列表(供追溯面板 / 笔记侧"被引用"角标使用)
export function listByNote(noteId) {
  return request({
    url: '/system/notelink/byNote/' + noteId,
    method: 'get'
  })
}

// 新增笔记链接
export function addNotelink(data) {
  return request({
    url: '/system/notelink',
    method: 'post',
    data: data
  })
}

// 修改笔记链接
export function updateNotelink(data) {
  return request({
    url: '/system/notelink',
    method: 'put',
    data: data
  })
}

// 删除笔记链接
export function delNotelink(id) {
  return request({
    url: '/system/notelink/' + id,
    method: 'delete'
  })
}
