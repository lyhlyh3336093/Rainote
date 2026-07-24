<template>
  <a-form ref="form" :model="formState" name="validate_other" @finish="onFinish" :label-col="{ span: 4 }"
    :wrapper-col="{ span: 24 }">
    <a-form-item :rules="[{ required: true, message: '请输入字段标题' }]" label="字段标题" name="title">
      <a-input v-model:value="formState.title" placeholder="请输入字段标题" allowClear />
    </a-form-item>
    <a-form-item :rules="[{ required: true, message: '请选择字段类型' }]" label="字段类型" name="type">
      <a-select allowClear v-model:value="formState.type" placeholder="请选择字段类型">
        <template v-for="(item, key) in fields" :key="item.field">
          <a-select-option :value="Number(key)">{{ item.field }}
          </a-select-option>
        </template>
      </a-select>
    </a-form-item>
    <a-form-item v-if="[FieldEnum.单选, FieldEnum.多选].includes(formState.type)" :rules="[
      {
        required: true,
        message: '请输入选项,多个用逗号隔开',
      },
    ]" label="选项配置" name="property">
      <a-input allowClear v-model:value="formState.property" placeholder="请输入选项,多个用逗号隔开" />
    </a-form-item>
    <a-form-item v-if="[FieldEnum.双向关联, FieldEnum.语义关联, FieldEnum.lookUp].includes(formState.type)" :rules="[
      {
        required: true,
        message: '请选择需要关联的数据表'
      },
    ]" label="选项配置" name="property">
      <a-select allowClear v-model:value="formState.property" placeholder="请选择需要关联的数据表" showSearch
        :filterOption="filterOption" :options="options">
      </a-select>

    </a-form-item>
    <template v-if="[FieldEnum.数学公式].includes(formState.type)">
      <a-form-item :rules="[
        {
          required: true,
          message: '请选择公式A列',
        },
      ]" label="公式A列" name="columnAId">
        <a-select allowClear v-model:value="formState.columnAId" placeholder="请选择公式A列" showSearch
          :filterOption="filterOption">
          <a-select-option v-for="item in columns" :key="item.id" :value="item.id">{{ item.name }}
          </a-select-option>
        </a-select>
      </a-form-item>

      <a-form-item :rules="[
        {
          required: true,
          message: '请选择公式B列',
        },
      ]" label="公式B列" name="columnBId">
        <a-select allowClear v-model:value="formState.columnBId" placeholder="请选择公式B列" showSearch
          :filterOption="filterOption">
          <a-select-option v-for="item in columns" :key="item.id" :value="item.id">{{ item.name }}
          </a-select-option>
        </a-select>
      </a-form-item>

      <a-form-item :rules="[
        {
          required: true,
          message: '请输入数学公式',
        },
      ]" label="数学公式" name="expression">
        <a-textarea :rows="5" placeholder="数学公式只包含两个参数A和B,以及常用的数学运算符号，支持括号运算且识别运算优先级，用户不需要输入=，例：（A+B）/2,或者 （B-A）/B都是合法的"
          v-model:value="formState.expression" />
      </a-form-item>
    </template>
    <template v-if="[FieldEnum.集合运算].includes(formState.type)">
      <a-form-item :rules="[
        {
          required: true,
          message: '请选择计算类型',
        },
      ]" label="计算类型" name="calcType">
        <a-select allowClear v-model:value="formState.calcType" placeholder="请选择计算类型" showSearch
          :filterOption="filterOption">
          <a-select-option v-for="item in calcType" :key="item.value" :value="item.value">{{ item.label }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item :rules="[
        {
          required: true,
          message: '请选择公式A列',
        },
      ]" label="公式A列" name="columnAId">
        <a-select allowClear v-model:value="formState.columnAId" placeholder="请选择公式A列" showSearch
          :filterOption="filterOption">
          <a-select-option v-for="item in columns.filter(item => item.type === FieldEnum.双向关联)" :key="item.id"
            :value="item.id">{{ item.name }}
          </a-select-option>
        </a-select>
      </a-form-item>

      <a-form-item :rules="[
        {
          required: true,
          message: '请选择公式B列',
        },
      ]" label="公式B列" name="columnBId">
        <a-select allowClear v-model:value="formState.columnBId" placeholder="请选择公式B列" showSearch
          :filterOption="filterOption">
          <a-select-option v-for="item in columns.filter(item => item.type === FieldEnum.双向关联)" :key="item.id"
            :value="item.id">{{ item.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
    </template>
    <a-form-item style="text-align: right">
      <a-button v-if="formState.type === FieldEnum.双向关联 && formState.property" class="mr-2" @click="preview.click">查看数据
      </a-button>
      <a-button class="mr-2" @click="cancel">取消</a-button>
      <a-button html-type="submit" type="primary">确认</a-button>
    </a-form-item>
  </a-form>
  <a-modal width="70%" :footer="null" :title="`待关联 ${preview.related.name}`" v-model:open="preview.visible"
    destroyOnClose>
    <Related ref="relatedRef" :data="preview.related" />
  </a-modal>
</template>
<script lang="tsx">
import { storeToRefs } from "pinia";
import { defineComponent, reactive, inject, ref, watch } from "vue";
import Related from '../related/index.vue';
import { FieldEnum } from "@/enum";
import { useStore } from '../../../stores/menu';

export default defineComponent({
  name: 'edit-field',
  emits: ['visible'],
  components: { Related },
  props: {
    formState: Object,
    edit: Object,
  },
  setup(props, ctx) {
    const edit = ref(props.edit);
    const options = ref([]);
    const menuStore = useStore();
    const { noteList } = storeToRefs(menuStore)
    const { store }: any = inject("store");
    const datasheet = ref(inject("datasheet"));
    const { datasheetID, fields, columns, calcType } = storeToRefs(store);
    const formState = reactive({
      title: null,
      type: null,
      property: null,
      columnAId: null,
      columnBId: null,
      expression: null,
      calcType: null,
    });
    for (const key in props.formState) {
      formState[key] = props.formState[key];
    }

    if ([FieldEnum.数学公式, FieldEnum.集合运算].includes(props.formState.type)) {
      for (const key in props.formState['property']) {
        formState[key] = props.formState['property'][key];
      }
    }
    if (FieldEnum.lookUp === props.formState.type) {
      formState.property = props.formState['property']?.double_link_column_id;

    }

    watch(() => formState.type, val => {
      if (val === FieldEnum.语义关联) {
        options.value = noteList.value.map(item => {
          return {
            ...item,
            label: item.title,
            value: item.id
          }
        })

      }
      if ([FieldEnum.双向关联, FieldEnum.单向关联, FieldEnum.lookUp].includes(val)) {
        options.value = datasheet.value.state.all.map((item) => {
          return {
            ...item,
            label: item.name,
            value: item.id,
          };
        });
      }
    }, {
      deep: true,
      immediate: true
    })
    const preview = reactive({
      related: {} as any,
      visible: false,
      click() {
        preview.related = options.value.find(item => item.value === formState.property);
        preview.visible = true;
      }
    })
    const onFinish = (values: any) => {
      const { title, type, property } = values;
      let _property: any = {
        select: `${property}`.replace(/，/gi, ","),
      };
      if (type === FieldEnum.双向关联) {
        _property = {
          table_id: property,
          table_name: options.value.find((item) => item.value === property)
            .name,
        };
      }
      if (type === FieldEnum.语义关联) {
        _property = {
          note_id: property,
          note_name: options.value.find((item) => item.value === property)
            .title,
        };
      }
      if (![FieldEnum.单选, FieldEnum.多选, FieldEnum.单向关联, FieldEnum.双向关联, FieldEnum.语义关联].includes(type)) {
        _property = {};
      }
      if (type === FieldEnum.数学公式) {
        _property = {
          expression: formState.expression,
          columnAId: formState.columnAId,
          columnBId: formState.columnBId
        };
      }
      if (type === FieldEnum.集合运算) {
        _property = {
          calcType: formState.calcType,
          columnAId: formState.columnAId,
          columnBId: formState.columnBId
        };
      }
      if (type === FieldEnum.lookUp) {
        const target: any = options.value.find((item) => item.value === property);
        _property = {
          "source_column_id": property,
          "source_column_name": title,
          "double_link_column_id": target.value,
          "double_link_column_name": target.label

        };
      }
      let col: any = {
        id: null,
        type: type,
        dwtableId: datasheetID.value,
        name: title,
        property: _property,
      };

      if (edit.value) {
        col.id = edit.value.id;
        store?.updateColumn(col);
        edit.value = null;
      } else {
        delete col.id;
        store?.addColumn(col);
      }
      cancel();
    };
    const filterOption = (input: string, option: any) => {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0;
    };
    const cancel = () => {
      ctx.emit('visible', false);
      edit.value = null;
      for (const key in formState) {
        formState[key] = null;
      }
    };
    return {
      calcType,
      columns,
      options,
      cancel,
      fields,
      FieldEnum,
      preview,
      onFinish,
      formState,
      filterOption,
    };
  },
});
</script>
