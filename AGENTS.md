# AGENTS.md

本仓库中AI代理工作的入口。

## 知识库

- **`docs/solutions/`** —— 已沉淀的解决方案与经验，按类别组织（architecture-patterns、design-patterns、logic-errors、workflow-issues等）。解决问题前先在此检索；解决非平凡问题后在此沉淀。
- **`CONCEPTS.md`** —— 共享领域词汇表。定义项目专有术语（NoteColumn、Double Link Column、Lookup Column、Set Operation Column等），供 `docs/solutions/` 与对话引用而无需重新定义。

## 沉淀解决方案

当你解决了一个非平凡问题，通过 `ce-compound` skill 沉淀：它会做调研、交叉引用已有文档，并向 `docs/solutions/<类别>/` 写入带YAML frontmatter的结构化文档。使用 `ce-compound-refresh` 审计并更新陈旧文档。
