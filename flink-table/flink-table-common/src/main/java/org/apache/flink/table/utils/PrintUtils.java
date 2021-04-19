/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.utils;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.data.ArrayData;
import org.apache.flink.table.data.GenericArrayData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LocalZonedTimestampType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.SmallIntType;
import org.apache.flink.table.types.logical.TimeType;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.table.types.logical.TinyIntType;
import org.apache.flink.types.Row;
import org.apache.flink.util.StringUtils;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;

import javax.annotation.Nullable;

import java.io.PrintWriter;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.apache.flink.table.types.logical.utils.LogicalTypeChecks.getPrecision;

/** Utilities for print formatting. */
@Internal
public class PrintUtils {

    // constants for printing
    public static final int MAX_COLUMN_WIDTH = 30;
    public static final String NULL_COLUMN = "(NULL)";
    public static final String ROW_KIND_COLUMN = "op";
    private static final String COLUMN_TRUNCATED_FLAG = "...";
    private static final DateTimeFormatter SQL_TIMESTAMP_FORMAT =
            new DateTimeFormatterBuilder()
                    .append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral(' ')
                    .appendPattern("HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .toFormatter();

    private PrintUtils() {}

    /**
     * Displays the result in a tableau form.
     *
     * <p>For example:
     *
     * <pre>
     * +-------------+---------+-------------+
     * | boolean_col | int_col | varchar_col |
     * +-------------+---------+-------------+
     * |        true |       1 |         abc |
     * |       false |       2 |         def |
     * |      (NULL) |  (NULL) |      (NULL) |
     * +-------------+---------+-------------+
     * 3 rows in set
     * </pre>
     */
    public static void printAsTableauForm(
            ResolvedSchema resolvedSchema,
            Iterator<Row> it,
            PrintWriter printWriter,
            ZoneId sessionTimeZone) {
        printAsTableauForm(
                resolvedSchema,
                it,
                printWriter,
                MAX_COLUMN_WIDTH,
                NULL_COLUMN,
                false,
                false,
                sessionTimeZone);
    }

    /**
     * Displays the result in a tableau form.
     *
     * <p><b>NOTE:</b> please make sure the data to print is small enough to be stored in java heap
     * memory if the column width is derived from content (`deriveColumnWidthByType` is false).
     *
     * <p>For example: (printRowKind is true)
     *
     * <pre>
     * +----+-------------+---------+-------------+
     * | op | boolean_col | int_col | varchar_col |
     * +----+-------------+---------+-------------+
     * | +I |        true |       1 |         abc |
     * | -U |       false |       2 |         def |
     * | +U |       false |       3 |         def |
     * | -D |      (NULL) |  (NULL) |      (NULL) |
     * +----+-------------+---------+-------------+
     * 4 rows in set
     * </pre>
     *
     * @param resolvedSchema The schema of the data to print
     * @param it The iterator for the data to print
     * @param printWriter The writer to write to
     * @param maxColumnWidth The max width of a column
     * @param nullColumn The string representation of a null value
     * @param deriveColumnWidthByType A flag to indicate whether the column width is derived from
     *     type (true) or content (false).
     * @param printRowKind A flag to indicate whether print row kind info
     * @param sessionTimeZone The time zone of current session.
     */
    public static void printAsTableauForm(
            ResolvedSchema resolvedSchema,
            Iterator<Row> it,
            PrintWriter printWriter,
            int maxColumnWidth,
            String nullColumn,
            boolean deriveColumnWidthByType,
            boolean printRowKind,
            ZoneId sessionTimeZone) {
        if (!it.hasNext()) {
            printWriter.println("Empty set");
            printWriter.flush();
            return;
        }
        final List<Column> columns = resolvedSchema.getColumns();
        String[] columnNames = columns.stream().map(Column::getName).toArray(String[]::new);
        if (printRowKind) {
            columnNames =
                    Stream.concat(Stream.of(ROW_KIND_COLUMN), Arrays.stream(columnNames))
                            .toArray(String[]::new);
        }

        final int[] colWidths;
        if (deriveColumnWidthByType) {
            colWidths =
                    columnWidthsByType(
                            columns,
                            maxColumnWidth,
                            nullColumn,
                            printRowKind ? ROW_KIND_COLUMN : null);
        } else {
            final List<Row> rows = new ArrayList<>();
            final List<String[]> content = new ArrayList<>();
            content.add(columnNames);
            while (it.hasNext()) {
                Row row = it.next();
                rows.add(row);
                content.add(
                        rowToString(
                                row, nullColumn, printRowKind, resolvedSchema, sessionTimeZone));
            }
            colWidths = columnWidthsByContent(columnNames, content, maxColumnWidth);
            it = rows.iterator();
        }

        final String borderline = PrintUtils.genBorderLine(colWidths);
        // print border line
        printWriter.println(borderline);
        // print field names
        PrintUtils.printSingleRow(colWidths, columnNames, printWriter);
        // print border line
        printWriter.println(borderline);

        long numRows = 0;
        while (it.hasNext()) {
            String[] cols =
                    rowToString(
                            it.next(), nullColumn, printRowKind, resolvedSchema, sessionTimeZone);

            // print content
            printSingleRow(colWidths, cols, printWriter);
            numRows++;
        }

        // print border line
        printWriter.println(borderline);
        final String rowTerm = numRows > 1 ? "rows" : "row";
        printWriter.println(numRows + " " + rowTerm + " in set");
        printWriter.flush();
    }

    public static String[] rowToString(
            Row row, ResolvedSchema resolvedSchema, ZoneId sessionTimeZone) {
        return rowToString(row, NULL_COLUMN, false, resolvedSchema, sessionTimeZone);
    }

    public static String[] rowToString(
            Row row,
            String nullColumn,
            boolean printRowKind,
            ResolvedSchema resolvedSchema,
            ZoneId sessionTimeZone) {
        final int len = printRowKind ? row.getArity() + 1 : row.getArity();
        final List<String> fields = new ArrayList<>(len);
        if (printRowKind) {
            fields.add(row.getKind().shortString());
        }
        for (int i = 0; i < row.getArity(); i++) {
            final Object field = row.getField(i);
            final LogicalType fieldType =
                    resolvedSchema.getColumnDataTypes().get(i).getLogicalType();
            if (field == null) {
                fields.add(nullColumn);
            } else {
                fields.add(
                        StringUtils.arrayAwareToString(
                                normalizeTimestamp(field, fieldType, sessionTimeZone)));
            }
        }
        return fields.toArray(new String[0]);
    }

    /**
     * Normalizes field that contains TIMESTAMP and TIMESTAMP_LTZ type data.
     *
     * <p>This method supports array type and nested type.
     */
    private static Object normalizeTimestamp(
            Object field, LogicalType fieldType, ZoneId sessionTimeZone) {
        final LogicalTypeRoot typeRoot = fieldType.getTypeRoot();
        if (field == null) {
            return "null";
        }
        switch (typeRoot) {
            case TIMESTAMP_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                return formatTimestampData(field, fieldType, sessionTimeZone);
            case ARRAY:
                LogicalType elementType = ((ArrayType) fieldType).getElementType();
                if (field instanceof List) {
                    List<?> array = (List<?>) field;
                    List<Object> normalizedArray = new ArrayList<>(array.size());
                    for (int i = 0; i < array.size(); i++) {
                        normalizedArray.set(
                                i, normalizeTimestamp(array.get(i), elementType, sessionTimeZone));
                    }
                    return normalizedArray;
                } else if (field instanceof ArrayData) {
                    ArrayData array = (ArrayData) field;
                    Object[] normalizedArray = new Object[array.size()];
                    for (int i = 0; i < array.size(); i++) {
                        normalizedArray[i] =
                                normalizeTimestamp(
                                        array.getTimestamp(i, getPrecision(elementType)),
                                        elementType,
                                        sessionTimeZone);
                    }
                    return new GenericArrayData(normalizedArray);
                } else if (field.getClass().isArray()) {
                    // primitive type
                    if (field.getClass() == byte[].class) {
                        byte[] array = (byte[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else if (field.getClass() == short[].class) {
                        short[] array = (short[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else if (field.getClass() == int[].class) {
                        int[] array = (int[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else if (field.getClass() == long[].class) {
                        long[] array = (long[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else if (field.getClass() == float[].class) {
                        float[] array = (float[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else if (field.getClass() == double[].class) {
                        double[] array = (double[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else if (field.getClass() == boolean[].class) {
                        boolean[] array = (boolean[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else if (field.getClass() == char[].class) {
                        char[] array = (char[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    } else {
                        // non-primitive type
                        Object[] array = (Object[]) field;
                        Object[] normalizedArray = new Object[array.length];
                        for (int i = 0; i < array.length; i++) {
                            normalizedArray[i] =
                                    normalizeTimestamp(array[i], elementType, sessionTimeZone);
                        }
                        return normalizedArray;
                    }
                } else {
                    return field;
                }
            case ROW:
                if (fieldType instanceof RowType && field instanceof Row) {
                    Row row = (Row) field;
                    Row normalizedRow = new Row(row.getKind(), row.getArity());
                    for (int i = 0; i < ((RowType) fieldType).getFields().size(); i++) {
                        LogicalType type = ((RowType) fieldType).getFields().get(i).getType();
                        normalizedRow.setField(
                                i, normalizeTimestamp(row.getField(i), type, sessionTimeZone));
                    }
                    return normalizedRow;

                } else if (fieldType instanceof RowType && field instanceof RowData) {
                    RowData rowData = (RowData) field;
                    GenericRowData normalizedRowData =
                            new GenericRowData(rowData.getRowKind(), rowData.getArity());
                    for (int i = 0; i < ((RowType) fieldType).getFields().size(); i++) {
                        LogicalType type = ((RowType) fieldType).getFields().get(i).getType();
                        RowData.FieldGetter fieldGetter = RowData.createFieldGetter(type, i);
                        normalizedRowData.setField(
                                i,
                                normalizeTimestamp(
                                        fieldGetter.getFieldOrNull(rowData),
                                        type,
                                        sessionTimeZone));
                    }
                    return normalizedRowData;
                } else {
                    return field;
                }
            default:
                return field;
        }
    }

    /**
     * Formats the print content of TIMESTAMP and TIMESTAMP_LTZ type data, consider the user
     * configured time zone.
     */
    private static Object formatTimestampData(
            Object timestampField, LogicalType fieldType, ZoneId sessionTimeZone) {
        switch (fieldType.getTypeRoot()) {
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                if (timestampField instanceof java.sql.Timestamp) {
                    // conversion between java.sql.Timestamp and TIMESTAMP_WITHOUT_TIME_ZONE
                    return ((Timestamp) timestampField)
                            .toLocalDateTime()
                            .format(SQL_TIMESTAMP_FORMAT);
                } else if (timestampField instanceof java.time.LocalDateTime) {
                    return ((LocalDateTime) timestampField).format(SQL_TIMESTAMP_FORMAT);
                } else if (timestampField instanceof TimestampData) {
                    return ((TimestampData) timestampField)
                            .toLocalDateTime()
                            .format(SQL_TIMESTAMP_FORMAT);
                } else {
                    return timestampField;
                }
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                Instant instant = null;
                if (timestampField instanceof java.time.Instant) {
                    instant = ((Instant) timestampField);
                } else if (timestampField instanceof java.sql.Timestamp) {
                    Timestamp timestamp = ((Timestamp) timestampField);
                    // conversion between java.sql.Timestamp and TIMESTAMP_WITH_LOCAL_TIME_ZONE
                    instant =
                            TimestampData.fromEpochMillis(
                                            timestamp.getTime(), timestamp.getNanos() % 1000_000)
                                    .toInstant();
                } else if (timestampField instanceof TimestampData) {
                    instant = ((TimestampData) timestampField).toInstant();
                } else if (timestampField instanceof Integer) {
                    instant = Instant.ofEpochSecond((Integer) timestampField);
                } else if (timestampField instanceof Long) {
                    instant = Instant.ofEpochMilli((Long) timestampField);
                }
                if (instant != null) {
                    return instant.atZone(sessionTimeZone)
                            .toLocalDateTime()
                            .format(SQL_TIMESTAMP_FORMAT);
                } else {
                    return timestampField;
                }
            default:
                return timestampField;
        }
    }

    public static String genBorderLine(int[] colWidths) {
        StringBuilder sb = new StringBuilder();
        sb.append("+");
        for (int width : colWidths) {
            sb.append(EncodingUtils.repeat('-', width + 1));
            sb.append("-+");
        }
        return sb.toString();
    }

    private static int[] columnWidthsByContent(
            String[] columnNames, List<String[]> rows, int maxColumnWidth) {
        // fill width with field names first
        final int[] colWidths = Stream.of(columnNames).mapToInt(String::length).toArray();

        // fill column width with real data
        for (String[] row : rows) {
            for (int i = 0; i < row.length; ++i) {
                colWidths[i] = Math.max(colWidths[i], getStringDisplayWidth(row[i]));
            }
        }

        // adjust column width with maximum length
        for (int i = 0; i < colWidths.length; ++i) {
            colWidths[i] = Math.min(colWidths[i], maxColumnWidth);
        }

        return colWidths;
    }

    /**
     * Try to derive column width based on column types. If result set is not small enough to be
     * stored in java heap memory, we can't determine column widths based on column values.
     */
    public static int[] columnWidthsByType(
            List<Column> columns,
            int maxColumnWidth,
            String nullColumn,
            @Nullable String rowKindColumn) {
        // fill width with field names first
        final int[] colWidths = columns.stream().mapToInt(col -> col.getName().length()).toArray();

        // determine proper column width based on types
        for (int i = 0; i < columns.size(); ++i) {
            DataType type = columns.get(i).getDataType();
            int len;
            switch (type.getLogicalType().getTypeRoot()) {
                case TINYINT:
                    len = TinyIntType.PRECISION + 1; // extra for negative value
                    break;
                case SMALLINT:
                    len = SmallIntType.PRECISION + 1; // extra for negative value
                    break;
                case INTEGER:
                    len = IntType.PRECISION + 1; // extra for negative value
                    break;
                case BIGINT:
                    len = BigIntType.PRECISION + 1; // extra for negative value
                    break;
                case DECIMAL:
                    len =
                            ((DecimalType) type.getLogicalType()).getPrecision()
                                    + 2; // extra for negative value and decimal point
                    break;
                case BOOLEAN:
                    len = 5; // "true" or "false"
                    break;
                case DATE:
                    len = 10; // e.g. 9999-12-31
                    break;
                case TIME_WITHOUT_TIME_ZONE:
                    int precision = ((TimeType) type.getLogicalType()).getPrecision();
                    len = precision == 0 ? 8 : precision + 9; // 23:59:59[.999999999]
                    break;
                case TIMESTAMP_WITHOUT_TIME_ZONE:
                    precision = ((TimestampType) type.getLogicalType()).getPrecision();
                    len = timestampTypeColumnWidth(precision, type.getConversionClass());
                    break;
                case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                    precision = ((LocalZonedTimestampType) type.getLogicalType()).getPrecision();
                    len = timestampTypeColumnWidth(precision, type.getConversionClass());
                    break;
                default:
                    len = maxColumnWidth;
            }

            // adjust column width with potential null values
            colWidths[i] = Math.max(colWidths[i], Math.max(len, nullColumn.length()));
        }

        // add an extra column for row kind if necessary
        if (rowKindColumn != null) {
            final int[] ret = new int[columns.size() + 1];
            ret[0] = rowKindColumn.length();
            System.arraycopy(colWidths, 0, ret, 1, columns.size());
            return ret;
        } else {
            return colWidths;
        }
    }

    /**
     * Here we consider two popular class for timestamp: LocalDateTime and java.sql.Timestamp.
     *
     * <p>According to LocalDateTime's comment, the string output will be one of the following
     * ISO-8601 formats:
     * <li>{@code uuuu-MM-dd'T'HH:mm:ss}
     * <li>{@code uuuu-MM-dd'T'HH:mm:ss.SSS}
     * <li>{@code uuuu-MM-dd'T'HH:mm:ss.SSSSSS}
     * <li>{@code uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS}
     *
     *     <p>And for java.sql.Timestamp, the number of digits after point will be precision except
     *     when precision is 0. In that case, the format would be 'uuuu-MM-dd HH:mm:ss.0'
     */
    private static int timestampTypeColumnWidth(int precision, Class<?> conversionClass) {
        int base = 19; // length of uuuu-MM-dd HH:mm:ss
        if (precision == 0 && java.sql.Timestamp.class.isAssignableFrom(conversionClass)) {
            return base + 2; // consider java.sql.Timestamp
        } else if (precision <= 3) {
            return base + 4;
        } else if (precision <= 6) {
            return base + 7;
        } else {
            return base + 10;
        }
    }

    public static void printSingleRow(int[] colWidths, String[] cols, PrintWriter printWriter) {
        StringBuilder sb = new StringBuilder();
        sb.append("|");
        int idx = 0;
        for (String col : cols) {
            sb.append(" ");
            int displayWidth = getStringDisplayWidth(col);
            if (displayWidth <= colWidths[idx]) {
                sb.append(EncodingUtils.repeat(' ', colWidths[idx] - displayWidth));
                sb.append(col);
            } else {
                sb.append(truncateString(col, colWidths[idx] - COLUMN_TRUNCATED_FLAG.length()));
                sb.append(COLUMN_TRUNCATED_FLAG);
            }
            sb.append(" |");
            idx++;
        }
        printWriter.println(sb.toString());
        printWriter.flush();
    }

    private static String truncateString(String col, int targetWidth) {
        int passedWidth = 0;
        int i = 0;
        for (; i < col.length(); i++) {
            if (isFullWidth(Character.codePointAt(col, i))) {
                passedWidth += 2;
            } else {
                passedWidth += 1;
            }
            if (passedWidth > targetWidth) {
                break;
            }
        }
        String substring = col.substring(0, i);

        // pad with ' ' before the column
        int lackedWidth = targetWidth - getStringDisplayWidth(substring);
        if (lackedWidth > 0) {
            substring = EncodingUtils.repeat(' ', lackedWidth) + substring;
        }
        return substring;
    }

    public static int getStringDisplayWidth(String str) {
        int numOfFullWidthCh = (int) str.codePoints().filter(PrintUtils::isFullWidth).count();
        return str.length() + numOfFullWidthCh;
    }

    /**
     * Check codePoint is FullWidth or not according to Unicode Standard version 12.0.0. See
     * http://unicode.org/reports/tr11/
     */
    public static boolean isFullWidth(int codePoint) {
        int value = UCharacter.getIntPropertyValue(codePoint, UProperty.EAST_ASIAN_WIDTH);
        switch (value) {
            case UCharacter.EastAsianWidth.NEUTRAL:
            case UCharacter.EastAsianWidth.AMBIGUOUS:
            case UCharacter.EastAsianWidth.HALFWIDTH:
            case UCharacter.EastAsianWidth.NARROW:
                return false;
            case UCharacter.EastAsianWidth.FULLWIDTH:
            case UCharacter.EastAsianWidth.WIDE:
                return true;
            default:
                throw new RuntimeException("unknown UProperty.EAST_ASIAN_WIDTH: " + value);
        }
    }
}
