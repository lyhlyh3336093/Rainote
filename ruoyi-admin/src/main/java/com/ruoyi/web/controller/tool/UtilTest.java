package com.ruoyi.web.controller.tool;


import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.framework.web.domain.server.Sys;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;


import java.util.ArrayList;

import java.util.List;


public class UtilTest {



    public static void testCalcuteOperators() {
        ExpressionParser parser = new SpelExpressionParser();
        //算术运算
        Float two = parser.parseExpression("(1 + 1)*13").getValue(Float.class);
        System.out.println("第一题结果是： " + two);
        Float three = parser.parseExpression("(125 - 3 + 1)/13").getValue(Float.class);
        System.out.println("第二题结果是：" + three);
        Float four = parser.parseExpression("1 - (0-3)").getValue(Float.class);
        System.out.println("第三题结果是： " + four);
    }

    public static void calcute(String a,String b ,String exp) {
        ExpressionParser parser = new SpelExpressionParser();
        //算术运算
        String ex = exp.replace("a",a).replace("b",b);
        int two = parser.parseExpression(ex).getValue(Integer.class);
        System.out.println("计算结果为： " + two);


    }



    public static void setCalcute() {

        //集合运算
        List a = new ArrayList<String>();
        a.add("恭");
        a.add("喜");
        a.add("发");
        a.add("财");
        List b = new ArrayList<String>();
        b.add("恭");
        b.add("喜");
        b.add("中");
        b.add("奖");
        List disjunction = new ArrayList<String>();
        List subtract = new ArrayList<String>();
        List intersection = new ArrayList<String>();
        List union = new ArrayList<String>();
//        c.addAll(SetUtils.difference(a,b));

        disjunction = (List<String>) CollectionUtils.disjunction(a, b);
        subtract = (List<String>) CollectionUtils.subtract(a, b);
        intersection = (List<String>) CollectionUtils.intersection(a, b);
        union = (List<String>) CollectionUtils.union(a, b);

        System.out.println("并集为： " +union.toString() );
        System.out.println("交集为： " +intersection.toString() );
        System.out.println("差集为： " +subtract.toString() );
        System.out.println("补集为： " +disjunction.toString() );

    }





    public static void openFile(String path) {
//        File f1=new File("src\\aa.txt");//相对路径，如果没有前面的src，就在当前目录创建文件
        File f1=new File("D:\\ruoyi\\files\\text.txt");//相对路径，如果没有前面的src，就在当前目录创建文件
        if(f1.exists()) {
            System.out.println("文件已经存在");
        }else {
            try {
                f1.createNewFile();
                System.out.println("文件创建成功");
                System.out.println(f1.getAbsolutePath());
            } catch (Exception e) {
                // TODO: handle exception
            }
        }
    }

    public static void importNote(MultipartFile file)throws Exception{

            ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
            List<SysUser> userList = util.importExcel(file.getInputStream());
            System.out.println(userList.toString());
    }



    public static void main(String[] args) {
//        testCalcuteOperators();
//        calcute("30","40","(a-b)*2");

//        setCalcute();

//        sendMessage("1","2","在干嘛");
//        openFile("");

        //10.20:测试导入功能
        MultipartFile file = null;
        try{
            importNote(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


    }
}
