package org.zkoss.zss.model.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.model.AutoRollbackConnection;
import org.model.DBContext;
import org.model.DBHandler;
import org.zkoss.poi.hslf.model.Sheet;
import org.zkoss.zss.model.CellRegion;
import org.zkoss.zss.model.SCell;
import org.zkoss.zss.model.SSheet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/**
 * Created by Sajjadur on 11/14/2017.
 */
public class NavigationStructure {
    /**
     * Navigation bucket tree structure. Lazily constructed upon user clicking. The Model object has a reference to this Bucket Tree structure {@link Model#navSbuckets}. TODO: This link would break by reassigning in method init*. HOW TO FIX???
     */
    private ArrayList<Bucket> navBucketTree;
    /**
     * Temporarily store the computed bucket for user call. To be serialized by calling {@link #getSerializedBuckets}.
     */
    private ReturnBuffer returnBuffer;
    public int isNumericNavAttr;

    class ReturnBuffer {
        ArrayList<Bucket> buckets;
    }

    /**
     * Total number of data rows. Does not include the header line.
     */
    private int totalRows;

    public String getIndexString() {
        return indexString;
    }

    private String indexString;
    private Set<Integer> typeCheckedColumns;

    public void setCurrentSheet(SSheet currentSheet) {
        this.currentSheet = currentSheet;
    }

    SSheet currentSheet;

    /**
     * Must be set externally for the bucket generator to work.
     */
    private ArrayList<Object> recordList;
    public LinkedHashMap<String,Integer> uniqueValues;
    private int kHist;
    private String tableName;
    private Kryo kryo;
    private boolean useKryo;

    public NavigationStructure(String tableName) {
        this.tableName = tableName;
        this.kHist = 9;    // max # of histograms is kHist + 1
        kryo = new Kryo();
        this.useKryo = true;
        returnBuffer = new ReturnBuffer();
        typeCheckedColumns = new HashSet<>();
    }

    public Object createNavS(SSheet currentSheet) {
        Model model = currentSheet.getDataModel();
        String prevIndexString = getIndexString();
        setIndexString(model.indexString);
        ROM_Model rom_model = (ROM_Model) ((Hybrid_Model) model).tableModels.get(0).y;
        int columnIndex = Integer.parseInt(indexString.split("_")[1]) - 1;

        currentSheet.clearCache();
        try (AutoRollbackConnection connection = DBHandler.instance.getConnection()) {
            DBContext context = new DBContext(connection);

            if (rom_model.rowOrderTable.contains(indexString)) {
                rom_model.rowMapping = rom_model.rowOrderTable.get(indexString);
            } else {
                ArrayList<Integer> rowIds;
                CellRegion tableRegion = new CellRegion(0, columnIndex, currentSheet.getEndRowIndex(), columnIndex);
                rowIds = rom_model.rowMapping.getIDs(context, tableRegion.getRow(), tableRegion.getLastRow() - tableRegion.getRow() + 1);

                CountedBTree newOrder = new CountedBTree(context, null);
                newOrder.insertIDs(context, tableRegion.getRow(), rowIds);
                /*
                Clear the Navigation history if using a lot of memory
                 */
                if (rom_model.rowOrderTable.size() >= 3) {
                    rom_model.rowOrderTable.clear();
                }
                rom_model.rowOrderTable.put(indexString, newOrder);
                rom_model.rowMapping = newOrder;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        //create nav data structure
        if (prevIndexString != null && indexString.equals(prevIndexString)) {
            resetToRoot();
            return getSerializedBuckets();
        } else {
            clearAll();
        }
        setCurrentSheet(currentSheet);
        setTotalRows(currentSheet.getEndRowIndex() + 1);
        ArrayList<Object> recordList = new ArrayList<>();
        this.uniqueValues = new LinkedHashMap<String,Integer>();
        ((RCV_Model) model).navigationSortRangeByAttribute(currentSheet, 1, currentSheet.getEndRowIndex(), new int[]{columnIndex}, 0, recordList);
        setRecordList(recordList);
        initIndexedBucket(currentSheet.getEndRowIndex() + 1);
        return getSerializedBuckets();
    }

    /**
     * Convert a given column from string to semantic type. If done then do nothing.
     *
     * @param col
     * @return true: performance conversion; false: nothing was done.
     */
    public boolean typeConvertColumnIfHavent(AutoRollbackConnection connection, int col) {
        if (typeCheckedColumns.contains(col))
            return false;
        typeCheckedColumns.add(col);
        System.out.println("Type converting column " + col);
        CellRegion tableRegion = new CellRegion(0, col, totalRows - 1, col);
        ArrayList<SCell> result = (ArrayList<SCell>) currentSheet.getCells(tableRegion);
        result.forEach(x -> x.updateCellTypeFromString(connection, false));
        Collection<AbstractCellAdv> castCells = new ArrayList<>();
        result.forEach(x -> castCells.add((AbstractCellAdv) x));
        currentSheet.getDataModel().updateCells(new DBContext(connection), castCells);
        connection.commit();
        return true;
    }

    public ArrayList<Double> collectDoubleValues(int columnIndex, Bucket<String> subgroup) {
        int startRow = subgroup.getStartPos();
        int endRow = subgroup.getEndPos();
        try (AutoRollbackConnection connection = DBHandler.instance.getConnection()) {

            ArrayList<Double> doubleOnly = new ArrayList<>();
            ArrayList<SCell> result;
            {
                typeConvertColumnIfHavent(connection, columnIndex);
                CellRegion tableRegion = new CellRegion(startRow, columnIndex, endRow, columnIndex);
                result = (ArrayList<SCell>) currentSheet.getCells(tableRegion);
                result.sort(Comparator.comparing(SCell::getRowIndex));
            }

            /*
             * Populate the attribute.
             */
            result.forEach(x -> {
                Object val = x.getValue();
                if (val instanceof Double) {
                    doubleOnly.add((Double) val);
                }
            });
            return doubleOnly;
        }
    }

    /**
     * Query the path given the row number and diving level.
     *
     * @param row   The row number being queried.
     * @param level The maximum level to dive into. level=0 implies empty path.
     * @return A path of "Path": "0,1,2" like return object.
     */
    public HashMap<String, Object> getScrollPath(int row, int level) {
        if (row > totalRows || row < 1) {
            return null;
        }
        List<Bucket> subBuckets = this.navBucketTree;
        List<String> path = new ArrayList<>();
        while (subBuckets != null && level-- > 0) {
            for (int i = 0; i < subBuckets.size(); i++) {
                if (row < subBuckets.get(i).endPos) {
                    path.add(String.valueOf(i));
                    subBuckets = subBuckets.get(i).children;
                }
            }
        }
        HashMap<String, Object> returnJson = new HashMap<>();
        returnJson.put("Path", String.join(",", path));
        return returnJson;
    }

    public void resetToRoot() {
        returnBuffer.buckets = navBucketTree;
    }

    public void clearAll() {
        if (navBucketTree != null)
            navBucketTree.clear();
        recordList = null;
    }

    private static int[] listToArray(List<Integer> theList) {
        int[] arr = new int[theList.size()];
        for (int i = 0; i < theList.size(); i++) {
            arr[i] = theList.get(i);
        }
        return arr;
    }

    public Object getNavChildrenWithContext(int[] paths) {
        HashMap<String, Object> ret = new HashMap<>();
        List<Integer> prevPath = new ArrayList<>();
        List<Integer> laterPath = new ArrayList<>();
        this.computeOnDemandBucket(paths, prevPath, laterPath);
        ret.put("breadCrumb", getStringPath(paths));
        HashMap<String, Object> contextStruct = new HashMap<>();
        contextStruct.put("breadCrumb", getStringPath(listToArray(prevPath)));
        contextStruct.put("path", prevPath);
        ret.put("prev", contextStruct);
        contextStruct = new HashMap<>();
        contextStruct.put("breadCrumb", getStringPath(listToArray(laterPath)));
        contextStruct.put("path", laterPath);
        ret.put("later", contextStruct);
        ret.put("buckets", getSerializedBuckets());
        return ret;
    }

    /**
     * Serialize the newly created buckets to JSON format.
     *
     * @return
     */
    public Object getSerializedBuckets() {
        class ScrollingProtocol {
            public ArrayList<BucketGroup> data;

            class BucketGroup {
                public String name;
                public int[] rowRange;
                public int value;
                public int rate;
                public boolean clickable;

                /**
                 * Needed for serialization
                 */
                public BucketGroup() {
                }

                public BucketGroup(Bucket<String> item) {
                    name = item.name;
                    rowRange = new int[]{item.startPos, item.endPos};
                    value = item.size;
                    clickable = !item.isSingleton();
                    rate = 10;
                }
            }

            /**
             * Needed for serialization
             */
            public ScrollingProtocol() {
            }

            public ScrollingProtocol(ReturnBuffer ret) {
                data = new ArrayList<>();
                for (int i = 0; i < ret.buckets.size(); i++) {
                    data.add(new BucketGroup(ret.buckets.get(i)));
                }
            }
        }

        ScrollingProtocol obj = new ScrollingProtocol(returnBuffer);

        try {
            return obj.data;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Called when user select an index to start navigation. This function must be called after {@link #setRecordList}.
     */
    public void initIndexedBucket(int totalRows) {
        //this.totalRows = totalRows;
        navBucketTree = getNonOverlappingBuckets(1, this.totalRows - 1);
        returnBuffer.buckets = navBucketTree;
    }

    private boolean expandChild(Bucket subRoot) {
        if (subRoot.children == null) {
            if (!subRoot.minValue.equals(subRoot.maxValue)) {
                subRoot.setChildren(getNonOverlappingBuckets(subRoot.startPos, subRoot.endPos));
                return true;
            }
        }
        return false;
    }

    /**
     * Compute or fetch computed bucket list based on the input paths and store the result in {@link #returnBuffer}. Expect at least one level in the paths. This function assumes the target is expandable, i.e. it's user's job not to call this function on an end-node.
     */
    private void computeOnDemandBucket(int[] paths, List<Integer> prev, List<Integer> later) {
        if (computeOnDemandBucketIfEmptyPath(paths)) return;
        Bucket left = null, right = null, subRoot;
        List<Bucket> subBuckets = navBucketTree;
        for (int i = 0; i < paths.length; i++) {
            int path = paths[i];
            subRoot = subBuckets.get(path);
            if (path == subBuckets.size() - 1) {
                if (right != null) {
                    expandChild(right);
                    if (right.children != null) {
                        int seq = 0;
                        right = (Bucket) right.children.get(seq);
                        later.add(seq);
                    }
                }
            } else {
                later.clear();
                for (int j = 0; j < i; j++) {
                    later.add(paths[j]);
                }
                later.add(path + 1);
                right = subBuckets.get(path + 1);
            }
            if (path == 0) {
                if (left != null) {
                    expandChild(left);
                    if (left.children != null) {
                        int seq = left.children.size() - 1;
                        left = (Bucket) left.children.get(seq);
                        prev.add(seq);
                    }
                }
            } else {
                prev.clear();
                for (int j = 0; j < i; j++) {
                    prev.add(paths[j]);
                }
                prev.add(path - 1);
                left = subBuckets.get(path - 1);
            }
            subBuckets = subRoot.children;
        }

        subRoot = getSubRootBucket(paths);
        if (subRoot != null) {
            expandChild(subRoot);
            returnBuffer.buckets = subRoot.getChildren();
            if (right != null && expandChild(right))
                later.add(0);
            if (left != null && expandChild(left))
                prev.add(left.children.size() - 1);
        } else {
            returnBuffer.buckets = navBucketTree;
        }

    }

    private boolean computeOnDemandBucketIfEmptyPath(int[] paths) {
        if (paths.length == 0) {
            if (navBucketTree.size() == 0) {
                navBucketTree = getNonOverlappingBuckets(1, this.totalRows - 1);
                returnBuffer.buckets = navBucketTree;
            } else
                resetToRoot();
            return true;
        }
        return false;
    }

    /**
     * Find the bucket given the paths. Currently doesn't support empty paths. i.e. will return null. TODO: construct a root-level navBucketTree.
     *
     * @param paths
     * @return
     */
    public Bucket<String> getSubRootBucket(int[] paths) {
        if (paths.length == 0) return null;
        Bucket<String> subRoot = this.navBucketTree.get(paths[0]);
        for (int i = 1; i < paths.length; i++) {
            subRoot = subRoot.getChildren().get(paths[i]);
        }
        return subRoot;
    }

    public List<String> getStringPath(int[] paths) {
        List<String> ret = new ArrayList<>();
        if (paths.length == 0)
            return ret;
        Bucket<String> subRoot = this.navBucketTree.get(paths[0]);
        ret.add(subRoot.toString());
        for (int i = 1; i < paths.length; i++) {
            subRoot = subRoot.getChildren().get(paths[i]);
            ret.add(subRoot.toString());
        }
        return ret;
    }

    public void setNavBucketTree(ArrayList<Bucket> navBucketTree) {
        this.navBucketTree = navBucketTree;
    }

    public void setIndexString(String str) {
        this.indexString = str;
    }

    /**
     * Set the RecordList of navigation structure. Assume input list is a list of Comparable and does not contain the header row. The function will add a dummy header row to align the index with the back-end row positional mapping.
     *
     * @param ls Input Arraylist<Comparable> excluding the header info.
     */
    public void setRecordList(ArrayList<Object> ls) {
        this.recordList = ls;
        recordList.add(0, null);
    }

    public void setTotalRows(int rows) {
        this.totalRows = rows;
    }

    public int getTotalRows() {
        return this.totalRows;
    }

    public ArrayList<Bucket> getNonOverlappingBuckets(int startPos, int endPos) {

        ArrayList<Bucket> bucketList = new ArrayList<>();
        int bucketSize = (endPos - startPos + 1) / kHist;

        if (bucketSize == 0) {
            bucketSize = 1;
        }
        int startIndex = startPos;

        for (int i = 0; i < kHist && startIndex < endPos + 1; i++) {
            //System.out.println("---------------BUCKET NO: "+i);
            Bucket bucket = new Bucket();
            bucket.minValue = recordList.get(startIndex);
            bucket.startPos = startIndex;
            if (startIndex + bucketSize - 1 < endPos + 1) {
                bucket.maxValue = recordList.get(startIndex + bucketSize - 1);
                bucket.endPos = startIndex + bucketSize - 1;
            } else {
                bucket.maxValue = recordList.get(endPos);
                bucket.endPos = endPos;

            }
            int[] start_end;
            start_end = getStartEnd(bucket.endPos);
            int boundary_inc = 0;//count maxValue in next bucket
            int boundary_dec = 0;//count maxValue in current bucket
            boundary_dec = bucket.endPos - start_end[0] + 1;
            boundary_inc = start_end[1] - bucket.endPos + 1;
            if (bucket.maxValue.equals(bucket.minValue)) {
                bucket.maxValue = recordList.get(start_end[1]);
                bucket.endPos = start_end[1];
                bucket.size = bucket.endPos - bucket.startPos + 1;
                startIndex += bucket.size;
                bucket.setName(false);
                bucket.setId();
                bucketList.add(bucket);
            } else {
                if (boundary_dec >= boundary_inc)//keep everything in current bucket
                {
                    bucket.maxValue = recordList.get(start_end[1]);
                    bucket.endPos = start_end[1];
                } else {
                    bucket.maxValue = recordList.get(start_end[0] - 1);
                    bucket.endPos = start_end[0] - 1;
                }

                bucket.size = bucket.endPos - bucket.startPos + 1;
                startIndex += bucket.size;
                bucket.setName(false);


                bucket.setId();
                bucketList.add(bucket);
            }
        }

        if (startIndex < endPos + 1) {
            Bucket bucket = new Bucket();
            bucket.minValue = recordList.get(startIndex);
            bucket.maxValue = recordList.get(endPos);
            bucket.startPos = startIndex;
            bucket.endPos = endPos;
            bucket.size = endPos - startIndex + 1;
            bucket.setName(false);
            bucket.setId();
            bucketList.add(bucket);
        }
        return bucketList;
    }

    private LinkedHashSet generateBucketKeys(Bucket bucket) {
        LinkedHashSet<String> lhs = new LinkedHashSet<String>();;

        int keyIndex = bucket.startPos;
        int keyCount = uniqueValues.get(recordList.get(keyIndex));
        lhs.add((String) recordList.get(keyIndex));
        keyIndex +=keyCount;

        while(keyIndex <= bucket.endPos)
        {
            keyCount = uniqueValues.get(recordList.get(keyIndex));
            lhs.add((String) recordList.get(keyIndex));
            keyIndex +=keyCount;

        }
        return lhs;
    }

    /**
     * Return the position-based buckets.
     *
     * @param startPos location of starting row. First data row corresponds to startPos=1
     * @param endPos   Same as above. Last row is totalRows.
     * @return Bucket list.
     */
    public ArrayList<Bucket> getUniformBuckets(int startPos, int endPos) {
        ArrayList<Bucket> bucketList = new ArrayList<>();
        int bucketSize = (endPos - startPos + 1) / kHist;

        //System.out.println("(start,end): ("+startPos+","+endPos+"), BUCKET Size: "+bucketSize);

        if (bucketSize > 0) {
            int startIndex = startPos;
            for (int i = 0; i < kHist && startIndex < endPos + 1; i++) {
                //System.out.println("---------------BUCKET NO: " + i);
                Bucket bucket = new Bucket();
                //bucket.minValue = recordList.get(startIndex);
                bucket.startPos = startIndex;
                if (startIndex + bucketSize - 1 < endPos + 1) {
                    //bucket.maxValue = recordList.get(startIndex + bucketSize - 1);
                    bucket.endPos = startIndex + bucketSize - 1;
                } else {
                    //bucket.maxValue = recordList.get(endPos);
                    bucket.endPos = endPos;

                }

                bucket.size = bucket.endPos - bucket.startPos + 1;
                startIndex += bucket.size;
                bucket.setName(true);
                bucket.setId();
                bucketList.add(bucket);
            }

            if (startIndex < endPos + 1) {
                Bucket bucket = new Bucket();
                //bucket.minValue = recordList.get(startIndex);
                //bucket.maxValue = recordList.get(endPos);
                bucket.startPos = startIndex;
                bucket.endPos = endPos;
                bucket.size = endPos - startIndex + 1;
                bucket.setName(true);
                bucket.setId();

                bucketList.add(bucket);
            }
        }
        returnBuffer.buckets = bucketList;
        return bucketList;
    }


    /**
     * Return a list of keys that correspond to the flattened view of the bucket indicated by the input paths.
     *
     * @param paths Input paths determine the sub-root group to flatten over.
     * @return ArrayList of flattened key view.
     */
    public Map<String, Object> getFlattenView(int[] paths) {
        int cur, bound;
        {
            Bucket<String> bucket = getSubRootBucket(paths);
            if (bucket != null) {
                cur = bucket.startPos;
                bound = bucket.endPos;
            } else {
                cur = 1;
                bound = recordList.size() - 1;
            }
        }

        List<Integer> startRows = new ArrayList<>();
        List<Object> flattenView = new ArrayList<>();
        HashMap<String, Object> returnDict = new HashMap<>();
        returnDict.put("startRows", startRows);
        returnDict.put("flattenView", flattenView);
        while (cur < bound) {
            flattenView.add(recordList.get(cur));
            startRows.add(cur);
            cur = binarySearchNextUniqueKey(cur, false);
        }
        return returnDict;
    }

    public Map<String, Object> getBucketAggWithMemoization(Model model, Bucket<String> subGroup, int attrIndex, String agg_id, List<String> paras) {
        int startRow = subGroup.getStartPos();
        int endRow = subGroup.getEndPos();
        Map<String, Object> obj = model.getColumnAggregate(currentSheet, startRow, endRow, attrIndex, agg_id, paras, true);
        String formula = (String) obj.get("formula");
        Map<String, Object> aggMemMap = subGroup.aggMem;
        if (aggMemMap.containsKey(formula)) {
            obj.put("value", aggMemMap.get(formula));
        } else {
            obj = model.getColumnAggregate(currentSheet, startRow, endRow, attrIndex, agg_id, paras, false);
            aggMemMap.put(formula, obj.get("value"));
        }
        return obj;
    }

    /**
     * Return the leaves of  each bucket in the current path
     *
     * @param paths
     * @return
     */
    public Object getBucketsWithLeaves(int[] paths) {
        Bucket<String> subroot = getSubRootBucket(paths);
        List<Bucket> subgroups = subroot == null ? navBucketTree : subroot.getChildren();

        HashMap<String, Object> ret = new HashMap<>();

        ret.put("isNumeric",this.isNumericNavAttr);

        ArrayList<ArrayList<Object>> bucket_leaves_ls =  new ArrayList<ArrayList<Object>>();

        for(int i=0;i<subgroups.size();i++)
        {
            if(this.isNumericNavAttr==1)
            {
                ArrayList<Object> ls = new ArrayList<Object>();
                ls.add(subgroups.get(i).minValue);
                ls.add(subgroups.get(i).maxValue);
                bucket_leaves_ls.add(ls);
            }
            else
            {
                ArrayList<Object> ls = new ArrayList<Object>();

                //generate the leaves of a bucket
                if(subgroups.get(i).getLeaves()==null)
                    subgroups.get(i).setLeaves(generateBucketKeys(subgroups.get(i)));

                //if list of leaves is still null, that means the bucket itself is a leave
                if(subgroups.get(i).getLeaves()==null)
                {
                    ls.add(subgroups.get(i).minValue);
                }
                else
                {
                    for(Object o:subgroups.get(i).getLeaves())
                    {
                        ls.add(o);
                    }
                }
                bucket_leaves_ls.add(ls);

            }
        }
        ret.put("bucketArray",bucket_leaves_ls);
        return ret;
    }

    /**
     * Perform aggregation operation on groups of navigation.
     *
     * @param model
     * @param paths
     * @param attr_indices
     * @param agg_ids
     * @param paraList
     * @return
     */
    public List<List<Object>> navigationGroupAggregateValue(Model model, int[] paths, int[] attr_indices, String[] agg_ids, List<List<String>> paraList, List<Boolean> getCharts) {
        List<List<Object>> attrAggList = new ArrayList<>();
        List<Object> aggList = new ArrayList<>();

        Bucket<String> subroot = getSubRootBucket(paths);
        List<Bucket> subgroups = subroot == null ? navBucketTree : subroot.getChildren();
        for (int attr_i = 0; attr_i < attr_indices.length; attr_i++) {
            for (Bucket<String> subgroup : subgroups) {
                Map<String, Object> obj = getBucketAggWithMemoization(model, subgroup, attr_indices[attr_i], agg_ids[attr_i], paraList.get(attr_i));

                /*
                Process the chart information.
                 */
                if (getCharts.get(attr_i)) {
                    NavChartsPrototype.getPrototype().generateChartObject(model, this, obj, attr_indices[attr_i], subgroup, agg_ids[attr_i]);
                } else {
                    obj.put("chartType", -1);
                }
                aggList.add(obj);
            }
            attrAggList.add(aggList);
            aggList = new ArrayList<>();
        }
        return attrAggList;
    }

    /**
     * Binary search the starting index of the next unique key. start stride from 1, exponentially increase the stride until seeing diff value, then exponentially decrease stride to locate the desired index.
     *
     * @param cur current location in {@link #recordList}
     * @param dec boolean flag, true if search towards left, false if searching towards right.
     * @return Index of the next key. 0 (dec) or len (not dec) if the current key is the last one.
     */
    private int binarySearchNextUniqueKey(int cur, boolean dec) {
        if (dec) {
            return binarySearchNextUniqueKey(cur, 1);
        } else {
            return binarySearchNextUniqueKey(cur, recordList.size() - 1);
        }
    }

    /**
     * Search from cur position towards bound for next different key.
     *
     * @param cur   current position of key
     * @param bound The boundary of search inclusively.
     * @return The positional index of next different key.
     */
    private int binarySearchNextUniqueKey(int cur, int bound) {
        Object val = recordList.get(cur);
        int left, right;

        if (bound < cur) { // Looking for the left boundary
            { // Locate a range in log(n) time.
                right = cur;
                int stride = -1;
                left = right + stride;
                while (left >= bound && recordList.get(left).equals(val)) {
                    int tmp = left;
                    left = right + stride;
                    right = tmp;
                    stride *= 2;
                }
                if (left < bound) {
                    if (recordList.get(bound).equals(val))
                        return bound - 1;
                    else
                        left = bound;
                }
            }

            { // Binary search to find the first unequal value. At this stage, the next key must exist.
                while (left < right) {
                    int mid = (left + right) / 2;
                    if (recordList.get(mid).equals(val)) {
                        right = mid; // right guaranteed to decrease even right-left==1
                    } else {
                        left = mid + 1;
                    }
                }
                return left - 1;
            }

        } else { // Looking for the right boundary
            { // Locate a range in log(n) time.
                left = cur;
                int stride = 1;
                right = left + stride;
                while (right <= bound && recordList.get(right).equals(val)) {
                    int tmp = right;
                    right = left + stride;
                    left = tmp;
                    stride *= 2;
                }
                if (right > bound) {
                    if (recordList.get(bound).equals(val))
                        return bound + 1;
                    else
                        right = bound;
                }
            }

            { // Binary search to find the first unequal value. At this stage, the next key must exist.
                while (left < right) {
                    int mid = (left + right) / 2;
                    if (recordList.get(mid).equals(val)) {
                        left = mid + 1;
                    } else {
                        right = mid; // right guaranteed to decrease even right-left==1
                    }
                }
                return left;
            }
        }
    }

    private int[] getStartEnd(int currentPos) {
        int[] indexes = new int[2];
        indexes[0] = binarySearchNextUniqueKey(currentPos, true) + 1;
        indexes[1] = binarySearchNextUniqueKey(currentPos, false) - 1;
        return indexes;
    }

    public void printBuckets(List<Bucket> bucketList) {
        for (int i = 0; i < bucketList.size(); i++) {
            System.out.println("Bucket " + (i + 1));
            System.out.println("Max: " + bucketList.get(i).maxValue);
            System.out.println("Min: " + bucketList.get(i).minValue);
            System.out.println("start: " + bucketList.get(i).startPos);
            System.out.println("end: " + bucketList.get(i).endPos);
            System.out.println("Size: " + bucketList.get(i).size);
            System.out.println("children: " + bucketList.get(i).getChildrenCount());
            printBuckets(bucketList.get(i).getChildren());
        }
    }

    public void writeJavaObject(ArrayList<Bucket<String>> object_ls) { // TODO: serialize newly created structures.
        String className = object_ls.get(0).getClass().getName();

        PreparedStatement pstmt = null;

        String createTable = (new StringBuffer())
                .append("CREATE TABLE IF NOT EXISTS ")
                .append(tableName + "_nav")
                .append("(id INT PRIMARY KEY,data BYTEA)")
                .toString();

        try (AutoRollbackConnection connection = DBHandler.instance.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute(createTable.toString());
            connection.commit();
            // set input parameters
            StringBuffer navSB = new StringBuffer();
            for (int i = 0; i < object_ls.size(); i++) {
                navSB.append("INSERT into " + tableName + "_nav (id,data) values(?,?)");

                pstmt = connection.prepareStatement(navSB.toString());
                pstmt.setInt(1, i);

                if (useKryo) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    Output out = new Output(byteArrayOutputStream);
                    kryo.writeObject(out, object_ls.get(i));
                    pstmt.setBytes(2, out.toBytes());
                    out.close();
                    byteArrayOutputStream.close();
                } else {
                    ObjectMapper mapper = new ObjectMapper();
                    Object o = object_ls.get(i);
                    String value = mapper.writeValueAsString(o);
                    pstmt.setBytes(2, value.getBytes());
                }

                pstmt.executeUpdate();
                navSB = new StringBuffer();

            }

            connection.commit();
            pstmt.close();

            System.out.println("writeJavaObject: done serializing: " + className);
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    public ArrayList<Bucket<String>> readJavaObject(String dataTable) {

        ArrayList<Bucket<String>> bucketList = new ArrayList<Bucket<String>>();

        PreparedStatement pstmt = null;
        try (AutoRollbackConnection connection = DBHandler.instance.getConnection()) {

            StringBuffer navSB = new StringBuffer();
            navSB.append("SELECT data from " + dataTable + "_nav ORDER BY id");
            pstmt = connection.prepareStatement(navSB.toString());

            ResultSet rs = pstmt.executeQuery();
            try {
                while (rs.next()) {
                    if (useKryo) {
                        Input in = new Input(rs.getBytes(1));
                        Bucket<String> object = new Bucket<String>();
                        object = kryo.readObject(in, object.getClass());
                        in.close();
                        bucketList.add(object);
                    } else {
                        ObjectMapper mapper = new ObjectMapper();
                        Bucket<String> object = new Bucket<String>();
                        String value = new String(rs.getBytes(1));
                        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                        object = mapper.readValue(value, Bucket.class);
                        bucketList.add(object);
                    }

                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            rs.close();
            pstmt.close();
            //String className = bucketList.get(0).getClass().getName();
            System.out.println("readJavaObject: done de-serializing: ");

        } catch (SQLException e) {
            e.printStackTrace();
        }

        //printBuckets(bucketList);
        return bucketList;
    }

    public ArrayList<Bucket> recomputeNavS(String bucketName, ArrayList<Bucket> navSbuckets, ArrayList<Bucket> newList) {
        ArrayList<Bucket> newNavs = navSbuckets;

        for (int i = 0; i < newNavs.size(); i++) {
            Bucket<String> newParent = updateParentBucket(bucketName, newNavs.get(i), newList);
            if (newParent != null) {
                newNavs.remove(i);
                newNavs.add(i, newParent);
                return newNavs;
            }
        }

        return newNavs;

    }

    private Bucket<String> updateParentBucket(String bucketName, Bucket parent, ArrayList<Bucket> children) {
        System.out.println("Calling: " + parent.getName());
        if (parent.getName().equals(bucketName)) {
            parent.setChildren(children);
            return parent;
        }

        if (parent.children == null)
            return null;

        for (int i = 0; i < parent.children.size(); i++) {
            Bucket<String> newParent = updateParentBucket(bucketName, (Bucket) parent.children.get(i), children);
            if (newParent != null) {
                parent.children.remove(i);
                parent.children.add(i, newParent);
                return parent;
            }
        }
        return null;
    }
}
