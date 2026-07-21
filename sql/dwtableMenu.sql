-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表', '3', '1', 'dwtable', 'system/dwtable/index', 1, 0, 'C', '0', '0', 'system:dwtable:list', '#', 'admin', sysdate(), '', null, '多维表格数据表菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:dwtable:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:dwtable:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:dwtable:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:dwtable:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:dwtable:export',       '#', 'admin', sysdate(), '', null, '');