package com.ruoyi.common.utils.file;


import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class ExcelImportUtils {
    /**
     * 导入Excel文件，并转换为对象列表
     * @param filePath Excel文件路径
     * @param clazz 目标对象类型
     * @param sheetIndex 工作表索引，默认0
     * @param columnMapping 列索引到属性名的映射，如果不提供，则使用第一行的列名作为属性名
     * @return 对象列表
     */
    public static <T> List<T> importFromExcel(String filePath, Class<T> clazz, int sheetIndex, Map<Integer, String> columnMapping) throws IOException {
        // 读取工作簿
        Workbook workbook = WorkbookFactory.create(new File(filePath));
        Sheet sheet = workbook.getSheetAt(sheetIndex);

        // 获取标题行（第一行）
        Row titleRow = sheet.getRow(0);
        int numberOfCells = titleRow.getLastCellNum();

        // 如果没有提供映射，则使用标题行的列名作为属性名（列索引->列名字符串）
        if (columnMapping == null) {
            columnMapping = new HashMap<>();
            for (int i = 0; i < numberOfCells; i++) {
                Cell cell = titleRow.getCell(i);
                String columnName = cell.getStringCellValue();
                columnMapping.put(i, columnName);
            }
        }

        // 准备数据列表
        List<T> result = new ArrayList<>();

        // 从第二行开始遍历数据行
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            T obj = createObjectFromRow(row, clazz, columnMapping);
            if (obj != null) {
                result.add(obj);
            }
        }

        workbook.close();
        return result;
    }

    public static <T> T createObjectFromRow(Row row, Class<T> clazz, Map<Integer, String> columnMapping) {
        T obj = null;
        try {
            obj = clazz.newInstance();
            for (Map.Entry<Integer, String> entry : columnMapping.entrySet()) {
                int columnIndex = entry.getKey();
                String propertyName = entry.getValue();

                Cell cell = row.getCell(columnIndex);
                if (cell != null) {
                    String cellValue = getCellValueAsString(cell);
                    // 使用反射设置属性
                    setProperty(obj, propertyName, cellValue);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return obj;
    }

    public static String getCellValueAsString(Cell cell) {
        // 根据单元格类型，将其转换为字符串
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    // 防止科学计数法，转换为字符串
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    public static void setProperty(Object obj, String propertyName, String value) throws Exception {
        // 根据属性名，找到对应的setter方法
        String setterName = "set" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
        // 获取所有方法
        Method[] methods = obj.getClass().getMethods();
        for (Method method : methods) {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                Class<?> parameterType = method.getParameterTypes()[0];
                // 将字符串值转换为参数类型
                Object convertedValue = convertStringToType(value, parameterType);
                method.invoke(obj, convertedValue);
                break;
            }
        }
    }

    public static Object convertStringToType(String value, Class<?> targetType) {
        if (targetType == String.class) {
            return value;
        } else if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value);
        } else if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value);
        } else if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(value);
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value);
        } else if (targetType == Date.class) {
            // 这里简单处理，实际可能需要更复杂的日期格式解析
            try {
                // 假设日期字符串是标准格式，如"yyyy-MM-dd"
                return new SimpleDateFormat("yyyy-MM-dd").parse(value);
            } catch (ParseException e) {
                e.printStackTrace();
                return null;
            }
        } else {
            // 其他类型，可以继续扩展
            return null;
        }
    }
}