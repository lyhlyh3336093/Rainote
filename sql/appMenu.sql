-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('应用', '3', '1', 'app', 'system/app/index', 1, 0, 'C', '0', '0', 'system:app:list', '#', 'admin', sysdate(), '', null, '应用菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('应用查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:app:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('应用新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:app:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('应用修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:app:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('应用删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:app:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('应用导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:app:export',       '#', 'admin', sysdate(), '', null, '');