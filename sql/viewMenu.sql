-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('视图', '3', '1', 'view', 'system/view/index', 1, 0, 'C', '0', '0', 'system:view:list', '#', 'admin', sysdate(), '', null, '视图菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('视图查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:view:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('视图新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:view:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('视图修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:view:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('视图删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:view:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('视图导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:view:export',       '#', 'admin', sysdate(), '', null, '');