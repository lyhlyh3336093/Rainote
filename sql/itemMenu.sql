-- 菜单 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表内容', '3', '1', 'item', 'system/item/index', 1, 0, 'C', '0', '0', 'system:item:list', '#', 'admin', sysdate(), '', null, '多维表格数据表内容菜单');

-- 按钮父菜单ID
SELECT @parentId := LAST_INSERT_ID();

-- 按钮 SQL
insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表内容查询', @parentId, '1',  '#', '', 1, 0, 'F', '0', '0', 'system:item:query',        '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表内容新增', @parentId, '2',  '#', '', 1, 0, 'F', '0', '0', 'system:item:add',          '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表内容修改', @parentId, '3',  '#', '', 1, 0, 'F', '0', '0', 'system:item:edit',         '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表内容删除', @parentId, '4',  '#', '', 1, 0, 'F', '0', '0', 'system:item:remove',       '#', 'admin', sysdate(), '', null, '');

insert into sys_menu (menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
values('多维表格数据表内容导出', @parentId, '5',  '#', '', 1, 0, 'F', '0', '0', 'system:item:export',       '#', 'admin', sysdate(), '', null, '');