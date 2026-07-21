-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记角色和部门关联', '2000', '1', 'dept', 'system/dept/index', 1, 0, 'C', '0', '0', 'system:dept:list', '#', 'admin', sysdate(), '', null, '笔记角色和部门关联菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记角色和部门关联查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记角色和部门关联新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记角色和部门关联修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记角色和部门关联删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('笔记角色和部门关联导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:dept:export',       '#', 'admin', sysdate(), '', null, '');