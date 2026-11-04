-- =============================================
-- VIP 会员系统管理端菜单（U1）
-- 挂载于父目录 2000（同 taskMenu/deptMenu 等近期菜单惯例）
-- 权限点 system:vip:*
-- =============================================

-- ---------------------------------------------
-- 1. 充值申请审核（全状态订单列表）
-- ---------------------------------------------
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('充值审核', '2000', '10', 'vipOrder', 'system/vipOrder/index', 1, 0, 'C', '0', '0', 'system:vip:order:list', '#', 'admin', sysdate(), '', null, 'VIP充值申请审核菜单');

SELECT @orderParentId := LAST_INSERT_ID();

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('充值订单查询', @orderParentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:order:query',  '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('充值订单审核', @orderParentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:order:audit', '#', 'admin', sysdate(), '', null, '');

-- ---------------------------------------------
-- 2. 会员管理（状态列表 + 手动调整）
-- ---------------------------------------------
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('会员管理', '2000', '11', 'vipMember', 'system/vipMember/index', 1, 0, 'C', '0', '0', 'system:vip:member:list', '#', 'admin', sysdate(), '', null, 'VIP会员管理菜单');

SELECT @memberParentId := LAST_INSERT_ID();

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('会员查询', @memberParentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:member:query',   '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('会员调整', @memberParentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:member:adjust', '#', 'admin', sysdate(), '', null, '');

-- ---------------------------------------------
-- 3. 档位与收款码配置
-- ---------------------------------------------
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('VIP配置', '2000', '12', 'vipSetting', 'system/vipSetting/index', 1, 0, 'C', '0', '0', 'system:vip:tier:list', '#', 'admin', sysdate(), '', null, 'VIP档位与收款码配置菜单');

SELECT @tierParentId := LAST_INSERT_ID();

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('档位查询', @tierParentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:tier:query',  '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('档位新增', @tierParentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:tier:add',    '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('档位修改', @tierParentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:tier:edit',   '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('档位删除', @tierParentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:tier:remove', '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('收款码上传', @tierParentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:vip:tier:qrcode', '#', 'admin', sysdate(), '', null, '');
