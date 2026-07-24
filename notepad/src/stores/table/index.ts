import { defineStore } from 'pinia';
import { useStorage } from "@vueuse/core";
import { useFetch } from "../../hooks";
import { message } from "ant-design-vue";
import { clone } from 'xe-utils';
import { FieldEnum } from "../../enum";


export const session = useStorage('table', {
    rowHeight: '',
    group: '',
    view: {
        id: ""
    },
    datasheet: {
        id: ""
    }
});

//  列类型
//   字段类型:
//   1:多行文本
//   2:数字
//   3:单选
//   4:多选
//   5:日期
//   7:复选框
//   11:人员
//   13:电话号码
//   15:超链接
//   17:附件
//   18:单向关联
//   19:查找引用
//   20:公式
//   21:双向关联
//   22:地理位置
//   1001:创建时间
//   1002:最后更新时间
//   1003:创建人
//   1004:修改人
//   1005:自动编号
export const useStore = defineStore('table-index', {
    state: () => ({
        id: '',
        active: session.value,
        viewID: session.value.view?.id || '',
        datasheetID: session.value.datasheet?.id || '',
        columns: [],
        fields: {
            [FieldEnum.多行文本]: {
                field: '多行文本',
                value: 'np-input',
                placeholder: '请输入'
            },
            [FieldEnum.数字]: {
                field: '数字',
                value: 'np-input',
                props: {
                    type: 'number',
                    placeholder: '请输入'
                }
            },
            [FieldEnum.单选]: {
                field: '单选',
                value: 'np-select',
                props: {
                    placeholder: '请选择'
                }
            },
            [FieldEnum.多选]: {
                field: '多选',
                value: 'np-select',
                props: {
                    mode: 'multiple',
                    placeholder: '请选择'
                }
            },
            [FieldEnum.日期]: {
                field: '日期',
                value: 'np-picker',
                props: {
                    placeholder: '请选择'
                }
            },
            [FieldEnum.复选框]: {
                field: '复选框',
                value: 'np-checkbox'
            },
            // 11: {
            //     field: '人员',
            //     value: 'np-select',
            // },
            // 13: {
            //     field: '电话号码',
            //     value: 'np-input',
            //     props: {
            //         type: 'phone'
            //     }
            // },
            // 15: {
            //     field: '超链接',
            //     value: 'np-input',
            //     props: {
            //         type: 'link'
            //     }
            // },
            [FieldEnum.附件]: {
                field: '附件',
                value: 'np-upload',
            },
            // 18: {
            //     field: '单向关联',
            //     value: 'np-select
            // },
            // 19: {
            //     field: '查找引用'
            // },
            // 20: {
            //     field: '公式'
            // },
            [FieldEnum.双向关联]: {
                field: '双向关联',
                value: 'np-select',
                props: {
                    mode: 'multiple',
                    readonly: true,
                }
            },
            [FieldEnum.数学公式]: {
                field: '数学公式',
                value: null
            },
            [FieldEnum.集合运算]: {
                field: '集合运算',
                value: 'np-select',
                props: {
                    mode: 'tags',
                    readonly: true,
                }
            },
            [FieldEnum.语义关联]: {
                field: '语义关联',
                value: 'np-select',
                props: {
                    mode: 'tags',
                    readonly: true,
                }
            },
            [FieldEnum.lookUp]: {
                field: 'lookUp',
                value: 'np-select',
                props: {
                    mode: 'multiple',
                    readonly: true,
                }
            },
            [FieldEnum.链接多行文本]: {
                field: '链接多行文本',
                value: 'np-input',
                placeholder: '请输入'
            },
            // 22: {
            //     field: '地理位置'
            // },
            // 1001: {
            //     field: '创建时间',
            //     value: 'np-input'
            // },
            // 1002: {
            //     field: '最后更新时间',
            //     value: 'np-input'
            // },
            // 1003: {
            //     field: '创建人',
            //     value: 'np-input'
            // },
            // 1004: {
            //     field: '修改人',
            //     value: 'np-input'
            // },
            // 1005: {
            //     field: '自动编号',
            //     value: 'np-input'
            // },
        },
        group: {
            active: session.value?.group,
        },
        rowHeight: {
            active: session.value.rowHeight || '30',
            list: [
                {
                    label: '低',
                    value: '30'
                },
                {
                    label: '中等',
                    value: '60'
                },
                {
                    label: '高',
                    value: '90'
                },
                {
                    label: '超高',
                    value: '120'
                }
            ],
        },
        table: {
            loading: false,
            list: [],
            columnsId: [],
            columnsName: [],
            columnsType: [],
            tableData: [],
        },
        calcType: [
            {
                label: '补集',
                value: 'disjunction',
            },
            {
                label: '差集',
                value: 'subtract',
            },
            {
                label: '交集',
                value: 'intersection',
            },
            {
                label: '并集',
                value: 'union',
            }
        ],
        tableList: []
    }),
    actions: {
        // 获取多维表格列数据
        async getColumns(id: string = this.datasheetID) {
            this.table = {
                ...this.table,
                list: [],
                columnsId: [],
                columnsName: [],
                columnsType: [],
                tableData: [],
                tableData1: [],
            }
            const { data } = await useFetch(`/system/column/columnList?dwtableId=${id}`,).get().json();
            if (data?.value) {
                this.columns = data.value.data.map((item: { property: any; type: number; }) => {
                    if (item.property && item.property !== '') {
                        if (this.fields[item.type]?.props?.property) {
                            this.fields[item.type].props.property = eval("(" + JSON.parse(JSON.stringify(item.property)) + ")");
                            if ([FieldEnum.单选, FieldEnum.多选].includes(item.type)) {
                                this.fields[item.type].props.property.select = `${this.fields[item.type].props.property?.select}`.split(',').map((item => ({
                                    value: item,
                                    label: item
                                })))
                            }
                        }
                    }
                    return item;
                });
                const res = clone(this.columns).filter((item: { isShow: any; }) => !item.isShow);
                this.table.columnsId = res.map((item: { id: any; }) => `${item.id}`);
                this.table.columnsName = res.map((item: { name: any; }) => item.name);
                this.table.columnsType = res.map((item: { type: any; }) => item.type);
            }
        },
        async addColumn(value: any) {
            const { data } = await useFetch(`/system/column/add`,).post(value).json();
            if (data?.value) {
                this.getColumns(this.datasheetID);
                this.getTableList(this.datasheetID);
                message.success('新增成功');
            }
        },
        async updateColumn(value: any) {
            const { data } = await useFetch(`/system/column/update`,).post(value).json();
            if (data?.value) {
                this.getColumns(this.datasheetID);
                this.getTableList(this.datasheetID);
                message.success('修改成功');
            }
        },
        async deleteColumn(id: string) {
            const { data } = await useFetch(`/system/column/remove/${id}`).get().json();
            if (data?.value) {
                this.getColumns(this.datasheetID);
                this.getTableList(this.datasheetID);
                message.success('删除成功');
            }
        },
        async getTableList() {
            this.table.loading = true;
            this.table.tableData = [];
            const { data } = await useFetch(`/system/record/dataList?dwtableId=${this.datasheetID}`,).get().json();
            if (data?.value?.data?.length > 0) {
                const list = data.value.data.filter((item: {
                    tableData: any;
                }) => item?.tableData).sort((a, b) => a.id - b.id);
                this.table.list = list;
                this.table.tableData = list.map((item: {
                    [x: string]: { [x: string]: any; };
                    tableData: { [x: string]: any; };
                    id: any;
                }) => {
                    item.tableData['id'] = item.id;
                    for (const key in item.tableData) {
                        const column = this.columns.find((v: { id: any; }) => `${v.id}` === key);
                        if (column) {
                            if ([FieldEnum.多选, FieldEnum.双向关联, FieldEnum.集合运算].includes(column.type)) {
                                item['tableData'][column.id] = item['tableData'][column.id]?.split(',')?.filter((item: any) => item)
                            }
                            if (item['tableData'][column.id]?.length === 0) {
                                item['tableData'][column.id] = null;
                            }
                        }
                    }
                    return item.tableData;
                });
                this.columns = this.columns.map(column => {
                    if (column.type === FieldEnum.双向关联) {
                        if (this.table.tableData.some(item =>
                            item[column.id] || item[column.id] === ''
                        )) {
                            column.isEdit = true;
                        } else {
                            column.isEdit = false;
                        }
                    }
                    return column;
                })
                this.grouping(this.group.active)
            }
            this.table.loading = false;
        },
        datasheetChange(data: any) {
            const value: any = session.value;
            if (!value.datasheet) {
                value.datasheet = {};
            }
            value.datasheet.id = data;
            this.datasheetID = data;
        },
        viewChange(data = null) {
            const value: any = session.value;
            if (!value.view) {
                value.view = {};
            }
            if (!data) {
                delete value.view;
                delete value.group;
                this.viewID = '';
            } else {
                value.view.id = data;
                this.viewId = data;
            }
        },
        rowHeightChange(value: string) {
            session.value.rowHeight = value;
            this.rowHeight.active = value;
        },
        gourpChange(value: string) {
            session.value.group = value;
            this.group.active = value;
            this.grouping(value);
        },
        grouping(key = '') {
            const keys = [...new Set(this.table.tableData.map((item: {
                [x: string]: any;
            }) => item[key]))].filter(item => item);
            const parents = new Map();
            // 通过key设置当前分组的父级
            this.table.tableData.forEach((item: { [x: string]: any; }) => {
                if (keys.includes(item[key])) {
                    if (!parents.has(item[key])) {
                        parents.set(item[key], item);
                    }
                }
            });
            const parentsId = [...parents.values()].map((item) => item['id']);
            // 重置数据
            const newData = this.table.tableData.map((item: { [x: string]: any; parentId: any; children: any; }) => {
                // 删除子集 重置父级
                item.parentId = null;
                if (item.children) {
                    delete item.children;
                }
                if (!parentsId.includes(item['id']) && parents.has(item[key])) {
                    const id = parents.get(item[key]).id;
                    item.parentId = id;
                }
                return item;
            });
            this.table.tableData = newData;
        }

    },
});
