-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记部门', '2000', '1', 'dept', 'system/dept/index', 1, 0, 'C', '0', '0', 'system:dept:list', '#', 'admin', sysdate(), '', null, '笔记部门菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记部门查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记部门新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记部门修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记部门删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记部门导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:export',       '#', 'admin', sysdate(), '', null, '');