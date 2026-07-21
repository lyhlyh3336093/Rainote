-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('用户和笔记系统角色关联', '2000', '1', 'role', 'system/role/index', 1, 0, 'C', '0', '0', 'system:role:list', '#', 'admin', sysdate(), '', null, '用户和笔记系统角色关联菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('用户和笔记系统角色关联查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:role:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('用户和笔记系统角色关联新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:role:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('用户和笔记系统角色关联修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:role:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('用户和笔记系统角色关联删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:role:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('用户和笔记系统角色关联导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:role:export',       '#', 'admin', sysdate(), '', null, '');