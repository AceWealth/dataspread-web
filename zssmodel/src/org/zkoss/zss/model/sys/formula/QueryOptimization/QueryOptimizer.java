package org.zkoss.zss.model.sys.formula.QueryOptimization;

import org.zkoss.lang.Library;
import org.zkoss.zss.model.sys.formula.Exception.OptimizationError;
import org.zkoss.zss.model.sys.formula.Primitives.DataOperator;
import org.zkoss.zss.model.sys.formula.Primitives.GroupedDataOperator;
import org.zkoss.zss.model.sys.formula.Primitives.SingleDataOperator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryOptimizer {
    private QueryOptimizer(){}
    private static QueryOptimizer queryOptimizer = null ;
    public static QueryOptimizer getOptimizer(){
        if (queryOptimizer == null)
            queryOptimizer = new QueryOptimizer();
        return queryOptimizer;
    }
    private final static boolean doOptimization = Boolean.valueOf(Library.getProperty("QueryOptimizer.doOptimization"));
    private List<DataOperator> mergeDataOperators(List<QueryPlanGraph> graphs) throws OptimizationError {
        Map<String, List<DataOperator>> dataOperators = new HashMap<>();
        List<DataOperator> groupedDataNodes = new ArrayList<>();

        if (!doOptimization){
            for (QueryPlanGraph graph:graphs)
                groupedDataNodes.addAll(graph.dataNodes);
            return groupedDataNodes;
        }

        for (QueryPlanGraph graph:graphs){
            for (DataOperator data:graph.dataNodes){
                assert data instanceof SingleDataOperator;
                String key = data.getSheet().getId() +
                        ":" +
                        data.getRegion().getColumn() +
                        "-" +
                        data.getRegion().getLastColumn();
                dataOperators.computeIfAbsent(key, k -> new ArrayList<>()).add(data);
            }
        }

        for (List<DataOperator> value:dataOperators.values()){
            int maxRow =  value.get(0).getSheet().getEndRowIndex();
            assert value.size() * Math.log(value.size()) < maxRow;
            value.sort((o1, o2) -> o1.getRegion().getRow() == o2.getRegion().getRow() ?
                    o1.getRegion().getLastRow() - o2.getRegion().getLastRow()
                    :o1.getRegion().getRow() - o2.getRegion().getRow());
            List<DataOperator> temp = new ArrayList<>();
            temp.add(value.get(0));
            int currentMaxRow = value.get(0).getRegion().getLastRow();
            DataOperator data;
            for (int i = 1, isize = value.size(); i < isize; i++){
                data = value.get(i);
                int row = data.getRegion().getRow(), lastRow = data.getRegion().getLastRow();
                if (row > currentMaxRow){
                    if (temp.size() > 1){
                        groupedDataNodes.add(new GroupedDataOperator(temp));
                    }
                    else {
                        groupedDataNodes.add(temp.get(0));
                    }
                    temp = new ArrayList<>();
                    temp.add(data);
                }
                else {
                    int size = temp.size();
                    if (size > 0 && row == temp.get(size - 1).getRegion().getRow()
                            && lastRow == temp.get(size - 1).getRegion().getLastRow())
                        temp.get(size - 1).merge(data);
                    else
                        temp.add(data);
                }
                currentMaxRow = Math.max(currentMaxRow, lastRow);

            }
            if (temp.size() > 1){
                groupedDataNodes.add(new GroupedDataOperator(temp));
            }
            else {
                groupedDataNodes.add(temp.get(0));
            }
        }

        return groupedDataNodes;
    }

    public QueryPlanGraph optimize(List<QueryPlanGraph> graphs) throws OptimizationError {
        QueryPlanGraph ret = new QueryPlanGraph();

        ret.dataNodes = mergeDataOperators(graphs);

        return ret;
    }
}
