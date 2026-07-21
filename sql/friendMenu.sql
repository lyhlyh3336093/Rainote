-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('friend', '3', '1', 'friend', 'system/friend/index', 1, 0, 'C', '0', '0', 'system:friend:list', '#', 'admin', sysdate(), '', null, 'friend菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('friend查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:friend:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('friend新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:friend:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('friend修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:friend:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('friend删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:friend:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('friend导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:friend:export',       '#', 'admin', sysdate(), '', null, '');