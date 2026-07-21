package com.ruoyi.common.utils;

import com.ruoyi.common.constant.CacheConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 集合工具类
 *
 * @author ruoyi
 */
public class SetUtils {

    public static List origin = new ArrayList<>();

    public static List cloneArray(List comparer) {
        /// <summary>Clones the structure of the array and not the actual objects
        /// the same objects will be referenced in the new array.</summary>
        /// <returns type="Array">The cloned Array.</returns>


//        if(origin!=null){
//            origin.clear();
//        }

        origin.addAll(comparer);
        return origin;
    }

    ;

    public static List pushRange(Set range) {
        /// <summary>Appends an existing collection to this array</summary>
        /// <param name="range" type="Array, Object">The collection to append.</param>
        for (int i = 0; i < range.size(); i++) {
            origin.add(range.iterator());
        }
        return origin;
    }

    ;

    public static List remove(Object item, List comparer) {
        /// <summary>Removes an item from this array, if it not found nothing is done.</summary>
        /// <param name="item" type="Any">The item to be removed</param>
        /// <param name="comparer" type="Function">Optional function used to determine equality e.g. function(x, y) { return x.id === y.id; }</param>
        int i = 0;
        Set thisItem;
        int foundIndex = -1;

        if (comparer.size()==0) {
            foundIndex = 0;

        } else {
            for (; i < comparer.size(); i++) {
                if (comparer(item, comparer.get(i))) {
                    foundIndex = i;
                    break;
                }
            }
        }
        if (foundIndex != -1) {
            comparer.remove(comparer.get(i));
        }
        return comparer;
    }

    public static int findIndex(Object item, List comparer) {
        /// <summary>Finds the index of an item in this array, if none is found -1 is returned.</summary>
        /// <param name="item" type="Any">The item to be found</param>
        /// <param name="comparer" type="Function">Optional function used to determine equality e.g. function(x, y) { return x.id === y.id; }</param>
        /// <returns type="Number">The index</returns>

        int i = 0;

        if (comparer.size()==0) {
            return comparer.indexOf(item);
        }
        for (; i < comparer.size(); i++) {
            if (comparer(item, comparer.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean comparer(Object item, Object o) {
        if(item.equals(o)){
            return true;
        }else{
            return false;
        }
    }

    public static boolean isContains(Object item, List comparer) {
        /// <summary>Determines if an item exists in an array, if it not found nothing is done.</summary>
        /// <param name="item" type="Any">The item to be found</param>
        /// <param name="comparer" type="Function">Optional function used to determine equality e.g. function(x, y) { return x.id === y.id; }</param>
        /// <returns type="Boolean">true if item exists</returns>

        return SetUtils.findIndex(item, comparer) > -1;
    }

    ;

    public static List union(Set other, List comparer) {
        /// <summary>Returns a new set that contains all of the items that exist in both sets.</summary>
        /// <param name="other" type="Array, Object">The collection to be unioned</param>
        /// <param name="comparer" type="Function">Optional function used to determine equality e.g. function(x, y) { return x.id === y.id; }</param>
        /// <returns type="Array">A new Array</returns>
        List unionArr = SetUtils.cloneArray(comparer);
        int i = 0;
        Object item;
        for (; i < other.size(); i++) {
            item = other.stream().iterator();
            if (!SetUtils.isContains(item,comparer)) {
                unionArr.add(item);
            }
        }
        return unionArr;
    }

    ;

    public static List intersection(List other, List comparer) {
        /// <summary>Returns a new set that contains all of the items that are common to both sets.</summary>
        /// <param name="other" type="Array, Object">The collection to find the intersection</param>
        /// <param name="comparer" type="Function">Optional function used to determine equality e.g. function(x, y) { return x.id === y.id; }</param>
        /// <returns type="Array">A new Array</returns>
        List intersect = null;
        int i = 0;
        Object item;

        for (i = 0; i < comparer.size(); i++) {
            item = comparer.get(i);


//            if (other.contains(item,comparer)) {
            if (SetUtils.isContains(item,other)) {
                intersect.add(item);
            }
        }

        return intersect;
    }

    ;

    public static List difference(List other, List comparer) {
        /// <summary>Returns a new set that contains all of the items that exist in the first set and not in the second</summary>
        /// <param name="other" type="Array, Object">The collection to find the difference</param>
        /// <param name="comparer" type="Function">Optional function used to determine equality e.g. function(x, y) { return x.id === y.id; }</param>
        /// <returns type="Array">A new Array</returns>
        List diff = SetUtils.cloneArray(comparer);
        int i = 0;

        for (; i < other.size(); i++) {
            SetUtils.remove(other.get(i), diff);
        }
        return diff;
    }

    public static List distinct(List comparer) {
        /// <summary>Returns a new set that contains ony unique items</summary>
        /// <param name="comparer" type="Function">Optional function used to determine equality e.g. function(x, y) { return x.id === y.id; }</param>
        /// <returns type="Array">A new Array</returns>
        int i = 0;
        List arr = null;
        Object item;

        if (comparer.size() > 0) {
            comparer = null;
        }
        for (; i < comparer.size(); i++) {
            item = comparer.get(i);
            if (!SetUtils.isContains(item,comparer)) {
                arr.add(item);
            }
        }
        return arr;
    }

    /**
     * 设置cache key
     *
     * @param configKey 参数键
     * @return 缓存键key
     */
    public static String getCacheKey(String configKey) {
        return CacheConstants.SYS_DICT_KEY + configKey;
    }
}
