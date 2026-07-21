-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('列信息', '2000', '1', 'column', 'system/column/index', 1, 0, 'C', '0', '0', 'system:column:list', '#', 'admin', sysdate(), '', null, '列信息菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('列信息查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:column:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('列信息新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:column:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('列信息修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:column:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('列信息删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:column:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('列信息导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:column:export',       '#', 'admin', sysdate(), '', null, '');