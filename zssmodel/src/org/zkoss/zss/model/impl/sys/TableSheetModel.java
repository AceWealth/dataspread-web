package org.zkoss.zss.model.impl.sys;

import org.model.AutoRollbackConnection;
import org.model.DBContext;
import org.zkoss.json.JSONArray;
import org.zkoss.json.JSONObject;
import org.zkoss.zss.model.CellRegion;
import org.zkoss.zss.model.SSemantics;
import org.zkoss.zss.model.impl.AbstractCellAdv;
import org.zkoss.zss.model.impl.CellImpl;
import org.zkoss.zss.model.impl.CountedBTree;
import org.zkoss.zss.model.impl.PosMapping;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Math.round;

public class TableSheetModel {

    final static String ATTRIBUTES = "attributes";
    final static String TYPE = "type";
    final static String VALUES = "values";
    final static String TABLE_SHEET_ID = "table_sheet_id";
    final static String LABEL_CELLS = "label_cells";

    PosMapping rowMapping;
    String sheetName, tableName, linkId;
//    String sheetName, String tableName,
    TableSheetModel(DBContext context, String linkId){
//        this.sheetName = sheetName;
//        this.tableName = tableName;
        this.linkId = linkId;
        rowMapping = new CountedBTree(context, "LINK_" + linkId + "_row_idx");
//        colMapping = new CountedBTree(context, "LINK_" + linkId + "_col_idx");
    }

    JSONObject getCells(DBContext context, CellRegion fetchRegion, int rowOffset, int colOffset){

        JSONObject ret = new JSONObject();
        ret.put(TABLE_SHEET_ID, linkId);
        JSONArray attributes = new JSONArray();
        ret.put(ATTRIBUTES, attributes);
        JSONArray labels = new JSONArray();
        ret.put(LABEL_CELLS, attributes);

        ArrayList<Integer> rowIds;
        boolean includeHeader = (fetchRegion.getRow() == 0);
        if (includeHeader)
            rowIds = rowMapping.getIDs(context, fetchRegion.getRow(),
                    fetchRegion.getLastRow() - fetchRegion.getRow());
        else
            rowIds = rowMapping.getIDs(context, fetchRegion.getRow() - 1,
                    fetchRegion.getLastRow() - fetchRegion.getRow() + 1);


        HashMap<Integer, Integer> row_map = new HashMap<>(); // Oid -> row number
        int bound = rowIds.size();
        for (int i1 = 0; i1 < bound; i1++) {
            if (rowIds.get(i1) != -1) {
                row_map.put(rowIds.get(i1), fetchRegion.getRow() + i1 + (includeHeader ? 1 : 0));
            }
        }


        StringBuffer select = new StringBuffer("SELECT oid,* ")
                .append(" FROM ")
                .append(tableName)
                .append(" WHERE oid = ANY (?) ");

        AutoRollbackConnection connection = context.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement(select.toString())) {
            // Array inArrayRow = context.getConnection().createArrayOf(pkColumnType, rowIds);
            /* Assume an int array for now */
            Array inArrayRow = context.getConnection().createArrayOf("integer", rowIds.toArray());
            stmt.setArray(1, inArrayRow);

            ResultSet rs = stmt.executeQuery();

            if (includeHeader) {
                for (int i = fetchRegion.column; i <= fetchRegion.lastColumn; i++){
                    JSONArray cell = new JSONArray();
                    cell.add(rs.getMetaData().getColumnLabel(i + 2));
                    cell.add(rowOffset);
                    cell.add(colOffset + i);
                    labels.add(cell);
                }
            }

            ArrayList<JSONArray> attributeCells = new ArrayList<>();
            List<Integer> schema = new ArrayList<>();



            for (int i = fetchRegion.column; i <= fetchRegion.lastColumn; i++){
                JSONArray attributeCell = new JSONArray();
                attributeCells.add(attributeCell);
                JSONObject column = new JSONObject();
                attributes.add(column);
                column.put(TYPE, typeIdToString(rs.getMetaData().getColumnType(i + 2)));
                schema.add(rs.getMetaData().getColumnType(i + 2));
                column.put(VALUES, attributeCell);
            }
            while (rs.next()) {
                int oid = rs.getInt(1); /* First column is oid */
                int row = row_map.get(oid);

                for (int i = fetchRegion.column; i <= fetchRegion.lastColumn; i++) {
                    JSONArray cell = new JSONArray();
                    cell.add(getValue(rs,i, fetchRegion.column, schema));
                    cell.add(rowOffset + row);
                    cell.add(colOffset + i);
                    attributeCells.get(i - fetchRegion.column).add(cell);
                }
            }
            rs.close();
            stmt.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ret;
    }

    private Object getValue(ResultSet rs, int index, int offset, List<Integer> schema) throws Exception {
        switch (schema.get(index - offset)) {
            case Types.BOOLEAN:
                return rs.getBoolean(index + 2);
            case Types.BIGINT:
                return rs.getLong(index + 2);
            case Types.DECIMAL:
            case Types.DOUBLE:
            case Types.FLOAT:
            case Types.REAL:
            case Types.NUMERIC:
                return rs.getDouble(index + 2);
            case Types.INTEGER:
            case Types.TINYINT:
            case Types.SMALLINT:
                return rs.getInt(index + 2);
            case Types.LONGVARCHAR:
            case Types.VARCHAR:
            case Types.CHAR:
                return rs.getString(index + 2);
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
            default:
                throw new Exception("getValue: Unsupported type");
        }
    }
    private String typeIdToString(Integer type) throws Exception {
        switch (type) {
            case Types.BOOLEAN:
                return "BOOLEAN";
            case Types.BIGINT:
                return "BIGINT";
            case Types.DECIMAL:
                return "DECIMAL";
            case Types.DOUBLE:
                return "DOUBLE";
            case Types.FLOAT:
                return "FLOAT";
            case Types.REAL:
                return "REAL";
            case Types.NUMERIC:
                return "NUMERIC";
            case Types.INTEGER:
                return "INTEGER";
            case Types.TINYINT:
                return "TINYINT";
            case Types.SMALLINT:
                return "SMALLINT";
            case Types.LONGVARCHAR:
                return "LONGVARCHAR";
            case Types.VARCHAR:
                return "VARCHAR";
            case Types.CHAR:
                return "CHAR";
            case Types.DATE:
                return "DATE";
            case Types.TIME:
                return "TIME";
            case Types.TIMESTAMP:
                return "TIMESTAMP";
            default:
                throw new Exception("typeIdToString:Unsupported type");
        }
    }
}
