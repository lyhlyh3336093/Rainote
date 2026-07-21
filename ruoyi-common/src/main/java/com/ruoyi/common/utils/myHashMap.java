package com.ruoyi.common.utils;

import java.util.*;
//定义一个HashMapSon类，它继承HashMap类
public class myHashMap<K, V> extends HashMap<K,V> {
    private static final long serialVersionUID = -5894887960346129860L;
    // 重写HashMapSon类的toString()方法
    @Override
    public String toString() {
        Set<Map.Entry<K, V>> keyset = this.entrySet();
        Iterator<Map.Entry<K, V>> i = keyset.iterator();
        if (!i.hasNext())
            return "";
        StringBuffer buffer = new StringBuffer();
        buffer.append("{");//注意此程序与源代码的区别
        for (;;) {
            Map.Entry<K, V> me = i.next();
            K key = me.getKey();
            V value = me.getValue();
            buffer.append("\""+key.toString() + "\"" + ":");
            if(value instanceof String){
                buffer.append("\""+value + "\",");
            }else{
                buffer.append(value.toString() + ",");
            }

            if (!i.hasNext()){
                buffer.substring(0, buffer.length()-3);
                buffer.append("}");
                return buffer.toString();
            }
        }
    }
}
