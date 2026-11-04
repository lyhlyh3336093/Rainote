import request from '@/utils/request'

// 查询VIP充值订单列表
export function listOrders(query) {
  return request({
    url: '/system/vip/admin/order/list',
    method: 'get',
    params: query
  })
}

// 查询VIP充值订单详情
export function getOrder(orderId) {
  return request({
    url: '/system/vip/admin/order/' + orderId,
    method: 'get'
  })
}

// 审核VIP充值订单 { id, approve(true/false), adminRemark（驳回时必填） }
export function auditOrder(data) {
  return request({
    url: '/system/vip/admin/order/audit',
    method: 'put',
    data: data
  })
}

// 获取支付凭证图片（需鉴权下载，blob 方式）
export function getProofImage(proofUrl) {
  return request({
    url: '/system/vip/proof/download',
    method: 'get',
    params: { proofUrl },
    responseType: 'blob'
  })
}

// 查询VIP会员列表
export function listMembers(query) {
  return request({
    url: '/system/vip/admin/member/list',
    method: 'get',
    params: query
  })
}

// 调整VIP会员到期时间 { userId, expireTime: "yyyy-MM-dd HH:mm:ss" }
export function adjustMember(data) {
  return request({
    url: '/system/vip/admin/member/adjust',
    method: 'put',
    data: data
  })
}

// 查询VIP档位列表
export function listTiers(query) {
  return request({
    url: '/system/vip/admin/tier/list',
    method: 'get',
    params: query
  })
}

// 新增VIP档位
export function addTier(data) {
  return request({
    url: '/system/vip/admin/tier',
    method: 'post',
    data: data
  })
}

// 修改VIP档位
export function updateTier(data) {
  return request({
    url: '/system/vip/admin/tier',
    method: 'put',
    data: data
  })
}

// 删除VIP档位
export function deleteTier(tierId) {
  return request({
    url: '/system/vip/admin/tier/' + tierId,
    method: 'delete'
  })
}

// 查询收款码配置
export function getQrcode() {
  return request({
    url: '/system/vip/admin/qrcode',
    method: 'get'
  })
}

// 上传收款码（channel: wechat | alipay）
export function uploadQrcode(channel, file) {
  const data = new FormData()
  data.append('channel', channel)
  data.append('file', file)
  return request({
    url: '/system/vip/admin/qrcode',
    method: 'post',
    headers: { repeatSubmit: false },
    data: data
  })
}
