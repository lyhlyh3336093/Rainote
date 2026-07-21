-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('userInfo', '3', '1', 'userinfo', 'system/userinfo/index', 1, 0, 'C', '0', '0', 'system:userinfo:list', '#', 'admin', sysdate(), '', null, 'userInfo菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('userInfo查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:userinfo:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('userInfo新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:userinfo:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('userInfo修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:userinfo:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('userInfo删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:userinfo:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('userInfo导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:userinfo:export',       '#', 'admin', sysdate(), '', null, '');