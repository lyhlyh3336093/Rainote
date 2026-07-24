//  //1：页面 Block
//  PAGE("1","页面"),
//  //2：文本 Block
//  TEXT("2","文本"),
//  //3：标题 1 Block
//  HEADING1("3","标题1"),
//  //4：标题 2 Block
//  HEADING2("4","标题2"),
//  //5：标题 3 Block
//  HEADING3("5","标题3"),
//  //6：标题 4 Block
//  HEADING4("6","标题4"),
//  //7：标题 5 Block
//  HEADING5("7","标题5"),
//  //8：标题 6 Block
//  HEADING6("8","标题6"),
//  //9：标题 7 Block
//  HEADING7("9","标题7"),
//  //10：标题 8 Block
//  HEADING8("10","标题8"),
//  //11：标题 9 Block
//  HEADING9("11","标题9"),
//  //12：无序列表 Block
//  BULLET("12","无序列表"),

//  //13：有序列表 Block
//  ORDERED("13","有序列表"),
//  //14：代码块 Block
//  CODE("14","代码块"),
//  //15：引用 Block
//  QUOTE("15","引用"),
//  //16,公式 Block
//  EQUATION("16","公式"),
//  //17：待办事项 Block
//  TODO("17","待办事项"),
//  //18：多维表格 Block
//  BITABLE("18","多维表格"),
//  //19：高亮块 Block
//  CALLOUT("19","高亮块"),
//  //20：会话卡片 Block
//  CHATCARD("20","会话卡片"),
//  //21：流程图 & UML Block
//  DIAGRAM("21","流程图"),
//  //22：分割线 Block
//  divider("22","分割线"),
//  //23：文件 Block
//  FILE("23","文件"),
//  //24：分栏 Block
//  GRID("24","分栏"),
//  //25：分栏列 Block
//  GRIDCOLUMN("25","分栏列"),
//  //26：内嵌 Block
//  IFRAME("26","内嵌"),
//  //27：图片 Block
//  image("27","图片"),
//  //28：开放平台小组件 Block
//  ISV("28","开放平台小组件"),
//  //29：思维笔记 Block
//  MINDNOTE("29","思维笔记"),
//  //30：电子表格 Block
//  SHEET("30","电子表格"),
//  //31：表格 Block
//  TABLE("31","表格"),
//  //32：表格单元格 Block
//  TABLECELL("32","表格单元格"),
//  //33：视图 Block
//  VIEW("33","视图"),
//  //34：引用容器 Block
//  QUOTECONTAINER("34","引用容器"),
//  //35：任务 Block
//  TASK("35","任务"),
//  //36：OKR Block
//  OKR("36","OKR"),
//  //37：OKR Objective Block
//  OKROBJECTIVE("37","OKR Objective"),
//  //38：OKR Key Result Block
//  OKRKEYRESULT("38","OKR Key Result"),
//  //39：OKR 进展 Block
//  OKRPROGRESS("39","OKR 进展"),
//  //40：文档小组件
//  //PAGE("1","页面"),
//  //41：Jira Issue
//  JIRAISSUE("41","Jira Issue");
//  //999：未支持 Block
//  //PAGE("1","页面"),
export enum BlockType {
    header1 = 3,
    header2 = 4,
    header3 = 5,
    header4 = 6,
    header5 = 7,
    header6 = 8,
    header7 = 9,
    header8 = 10,
    header9 = 11,
    paragraph = 2,
    table = 31,
    unordered = 12,
    ordered = 13,
    code = 14,
    checklist = 17,
    divider = 22,
    basetable = 18,
    callout = 19,
    image = 27,
    mindmap = 29,
    file = 23
}

export enum BlockEvent {
    'block-added' = 'add',
    'block-changed' = 'update',
    'block-removed' = 'remove',
    "block-moved" = 'update'
}