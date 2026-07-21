-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('块元素', '3', '1', 'block', 'system/block/index', 1, 0, 'C', '0', '0', 'system:block:list', '#', 'admin', sysdate(), '', null, '块元素菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('块元素查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:block:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('块元素新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:block:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('块元素修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:block:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('块元素删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:block:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('块元素导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:block:export',       '#', 'admin', sysdate(), '', null, '');