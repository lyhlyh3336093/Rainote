<template>
  <div id="container" ref="container">

  </div>
</template>
<script name="Mindmap" setup lang="tsx">
import G6 from '@antv/g6';
import { onMounted, ref, nextTick } from "vue";

const container = ref(null);

const {
  Util
} = G6;

const colorArr = [
  '#5B8FF9',
  '#5AD8A6',
  '#5D7092',
  '#F6BD16',
  '#6F5EF9',
  '#6DC8EC',
  '#D3EEF9',
  '#DECFEA',
  '#FFE0C7',
  '#1E9493',
  '#BBDEDE',
  '#FF99C3',
  '#FFE0ED',
  '#CDDDFD',
  '#CDF3E4',
  '#CED4DE',
  '#FCEBB9',
  '#D3CEFD',
  '#945FB9',
  '#FF9845',
];

const rawData = {
  label: '中心节点',
  id: '0',
};
let tree = null;
let _evt = null;
G6.registerNode(
  'dice-mind-map-root', {
  jsx: (cfg) => {
    const width = Util.getTextSize(cfg.label, 16)[0] + 24;
    const stroke = cfg.style.stroke || '#096dd9';
    return `
      <group>
        <rect   draggable="true" style={{width: ${width + 10}, height: 22, stroke: ${stroke}, }} keyshape>
          <text style={{ fontSize: 14, marginLeft: 12, marginTop: 3 }}>${cfg.label}</text>
          <text style={{ marginLeft: ${width - 5
      }, marginTop:-15, cursor: 'pointer', opacity: ${cfg.hover ? 0.75 : 0
      }}} action="add">+</text>
        </rect>
      </group>
    `;
  },
  getAnchorPoints() {
    return [
      [0, 0.5],
      [1, 0.5],
    ];
  },
},
  'single-node',
);
G6.registerNode(
  'dice-mind-map-sub', {
  jsx: (cfg) => {
    const width = Util.getTextSize(cfg.label, 14)[0] + 24;
    const color = '#096dd9';

    return `
      <group>
        <rect draggable="true" style={{width: ${width + 24}, height: 22,stroke: ${color},}} keyshape>
          <text  draggable="true" style={{fontSize: 14, marginLeft: 12, marginTop: 3 }}>${cfg.label
      }</text>
          <text style={{ marginLeft: ${width - 8
      }, marginTop: -14,  cursor: 'pointer', opacity: ${cfg.hover ? 0.75 : 0
      }, next: 'inline' }} action="add">+</text>
          <text style={{ marginLeft: ${width - 4
      }, marginTop: -14,  cursor: 'pointer', opacity: ${cfg.hover ? 0.75 : 0
      }, next: 'inline' }} action="delete">-</text>
        </rect>

      </group>
    `;
  },
  getAnchorPoints() {
    return [
      [0, 0.965],
      [1, 0.965],
    ];
  },
},
  'single-node',
);
G6.registerNode(
  'dice-mind-map-leaf', {
  jsx: (cfg) => {
    const width = Util.getTextSize(cfg.label, 12)[0] + 24;
    const color = '#096dd9';
    return `
      <group>
        <rect draggable="true" style={{width: ${width + 20}, height: 26,stroke: ${color}}}>
          <text style={{ fontSize: 12, marginLeft: 12, marginTop: 3.5 }}>${cfg.label}</text>
              <text style={{ marginLeft: ${width - 8}, marginTop: -13,  cursor: 'pointer', opacity: ${cfg.hover ? 0.75 : 0
      }, next: 'inline' }} action="add">+</text>
              <text style={{ marginLeft: ${width - 4
      }, marginTop: -13,  cursor: 'pointer', opacity: ${cfg.hover ? 0.75 : 0
      }, next: 'inline' }} action="delete">-</text>
        </rect>
      </group>
    `;
  },
  getAnchorPoints() {
    return [
      [0, 0.965],
      [1, 0.965],
    ];
  },
},
  'single-node',
);
G6.registerBehavior('dice-mindmap', {
  getEvents() {
    return {
      'node:click': 'clickNode',
      'node:dblclick': 'editNode',
      'node:mouseenter': 'hoverNode',
      'node:mouseleave': 'hoverNodeOut',
    };
  },
  clickNode(evt) {
    const model = evt.item.get('model');
    const name = evt.target.get('action');
    switch (name) {
      case 'add':
        const newId =
          model.id +
          '-' +
          (((model.children || []).reduce((a, b) => {
            const num = Number(b.id.split('-').pop());
            return a < num ? num : a;
          }, 0) || 0) +
            1);
        evt.currentTarget.updateItem(evt.item, {
          children: (model.children || []).concat([{
            id: newId,
            direction: newId.charCodeAt(newId.length - 1) % 2 === 0 ? 'right' : 'left',
            label: '输入文字',
            type: 'dice-mind-map-leaf',
            color: model.color || colorArr[Math.floor(Math.random() * colorArr.length)],
          },]),
        });
        evt.currentTarget.layout(false);
        break;
      case 'delete':
        const parent = evt.item.get('parent');
        evt.currentTarget.updateItem(parent, {
          children: (parent.get('model').children || []).filter((e) => e.id !== model.id),
        });
        evt.currentTarget.layout(false);
        break;
      case 'edit':
        break;
      default:
        return;
    }
    tree.fitView();
  },
  editNode(evt) {
    const item = evt.item;
    const model = item.get('model');
    const {
      x,
      y
    } = item.calculateBBox();
    const graph = evt.currentTarget;
    const realPosition = evt.currentTarget.getClientByPoint(x, y);
    const el = document.createElement('div');
    const fontSizeMap = {
      'dice-mind-map-root': 24,
      'dice-mind-map-sub': 12,
      'dice-mind-map-leaf': 12,
    };
    const { width, height } = evt.item.getBBox();
    el.style.fontSize = fontSizeMap[model.type] + 'px';
    el.style.position = 'fixed';
    el.style.top = realPosition.y + 'px';
    el.style.left = realPosition.x + 'px';
    el.style.transformOrigin = 'top left';
    el.style.transform = `scale(${evt.currentTarget.getZoom()})`;
    const input = document.createElement('input');
    input.style.border = 'none';
    input.value = model.label;
    evt.item.hide()
    input.style.width = width - 2 + 'px';
    input.style.height = height - 2 + 'px';
    input.className = 'dice-input el-input';
    el.className = 'dice-input';
    el.appendChild(input);
    document.body.appendChild(el);
    const destroyEl = () => {
      document.body.removeChild(el);
      evt.item.show();
    };
    const clickEvt = (event) => {
      if (!(event.target && event.target.className && event.target.className.includes('dice-input'))) {
        window.removeEventListener('mousedown', clickEvt);
        window.removeEventListener('scroll', clickEvt);
        graph.updateItem(item, {
          label: input.value,
        });
        graph.layout(false);
        graph.off('wheelZoom', clickEvt);
        destroyEl();
      }
    };
    graph.on('wheelZoom', clickEvt);
    window.addEventListener('mousedown', clickEvt);
    window.addEventListener('scroll', clickEvt);
    input.addEventListener('keyup', (event) => {
      if (event.key === 'Enter') {
        clickEvt({
          target: {},
        });
      }
    });
  },
  hoverNode(evt) {
    evt.currentTarget.updateItem(evt.item, {
      hover: true,
    });
  },
  hoverNodeOut(evt) {
    evt.currentTarget.updateItem(evt.item, {
      hover: false,
    });
  },
});
G6.registerBehavior('scroll-canvas', {
  getEvents() {
    return {
      wheel: 'onWheel',
    };
  },

  onWheel(ev) {
    const {
      graph
    } = this;
    if (!graph) {
      return;
    }
    if (ev.ctrlKey) {
      const canvas = graph.get('canvas');
      const point = canvas.getPointByClient(ev.clientX, ev.clientY);
      let ratio = graph.getZoom();
      if (ev.wheelDelta > 0) {
        ratio += ratio * 0.05;
      } else {
        ratio *= ratio * 0.05;
      }
      graph.zoomTo(ratio, {
        x: point.x,
        y: point.y,
      });
    } else {
      const x = ev.deltaX || ev.movementX;
      const y = ev.deltaY || ev.movementY || (-ev.wheelDelta * 125) / 3;
      graph.translate(-x, -y);
    }
    ev.preventDefault();
  },
});

const dataTransform = (data) => {
  const changeData = (d, level = 0, color = '') => {
    const data = {
      ...d,
    };
    switch (level) {
      case 0:
        data.type = 'dice-mind-map-root';
        break;
      case 1:
        data.type = 'dice-mind-map-sub';
        break;
      default:
        data.type = 'dice-mind-map-leaf';
        break;
    }

    data.hover = false;

    if (color) {
      data.color = color;
    }

    if (level === 1 && !d.direction) {
      if (!d.direction) {
        data.direction = d.id.charCodeAt(d.id.length - 1) % 2 === 0 ? 'right' : 'left';
      }
    }

    if (d.children) {
      data.children = d.children.map((child) => changeData(child, level + 1, data.color));
    }
    return data;
  };
  return changeData(data);
};

const insterMenu = [
  {
    label: '插入子节点'
  },
  {
    label: '删除'
  }
]

const contextMenu = new G6.Menu({
  getContent(evt) {
    _evt = evt;
    const id = evt.item.getID();
    if (`${id}` === '0') return '<ul><li index="0">插入子节点</li></ul>';
    return `
    <ul>
      ${insterMenu.map((item, index) => (`<li id="${id}" index="${index}">${item.label}</li>`)).join(' ')}
    </ul>`;
  },
  handleMenuClick: (target, item) => {
    const index = target.getAttribute('index');
    const model: any = item.getModel();
    if (`${index}` === '0') {
      const newId =
        model.id +
        '-' +
        (((model.children || []).reduce((a, b) => {
          const num = Number(b.id.split('-').pop());
          return a < num ? num : a;
        }, 0) || 0) +
          1);
      _evt.currentTarget.updateItem(item, {
        children: (model.children || []).concat([{
          id: newId,
          direction: newId.charCodeAt(newId.length - 1) % 2 === 0 ? 'right' : 'left',
          label: '输入文字',
          type: 'dice-mind-map-leaf',
          color: model.color || colorArr[Math.floor(Math.random() * colorArr.length)],
        },]),
      });
    } else {
      const parent = _evt.item.get('parent');
      _evt.currentTarget.updateItem(parent, {
        children: (parent.get('model').children || []).filter((e) => e.id !== model.id),
      });
    }
    _evt.currentTarget.layout(false);
    _evt = null;
  },
  itemTypes: ['node'],
});
onMounted(() => {
  nextTick(() => {
    const { width, height } = container.value.getBoundingClientRect();
    tree = new G6.TreeGraph({
      container: container.value,
      width,
      height,
      fitView: true,
      fitViewPadding: [10, 20],
      layout: {
        type: 'mindmap',
        direction: 'LR',
        getId: function getId(d) {
          return d.id;
        },
        getHeight: () => {
          return 16;
        },
        getWidth: (node) => {
          return node.level === 0 ?
            Util.getTextSize(node.label, 14)[0] + 12 :
            Util.getTextSize(node.label, 12)[0];
        },
        getVGap: () => {
          return 10;
        },
        getHGap: () => {
          return 60;
        },
        getSide: (node) => {
          return node.data.direction;
        },
      },
      defaultEdge: {
        type: 'cubic-horizontal',
        style: {
          lineWidth: 2,
        },
      },
      minZoom: 0.5,
      modes: {
        default: ['drag-canvas', 'zoom-canvas', 'dice-mindmap'],
      },
      plugins: [contextMenu]
    });
    tree.data(dataTransform(rawData));
    tree.render();
    tree.fitView();
    tree.zoom(1);
  });



})

</script>
<style lang="scss">
#container {
  width: 100%;
  height: 100%;
}

div.dice-input {
  z-index: 99999;
  background: rgb(245, 245, 245);
  border: 1px solid #096dd9;
  display: flex;
  align-items: center;

  input {
    text-align: center;
    background: none;
    outline: none;
    border: none;
  }
}

.g6-component-contextmenu {
  li {
    list-style: none;
    padding: 10px;
    cursor: pointer;
  }
}
</style>