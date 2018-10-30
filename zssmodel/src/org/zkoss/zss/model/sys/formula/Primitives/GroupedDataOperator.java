package org.zkoss.zss.model.sys.formula.Primitives;

import javafx.util.Pair;
import org.zkoss.zss.model.sys.formula.Exception.OptimizationError;
import org.zkoss.zss.model.sys.formula.QueryOptimization.FormulaExecutor;
import org.zkoss.zss.range.SRange;
import org.zkoss.zss.range.impl.RangeImpl;

import java.util.List;

public class GroupedDataOperator extends DataOperator{

    List<Pair<Integer, Integer>> inEdgesRange, outEdgesRange;

    public GroupedDataOperator(List<DataOperator> dataOperators){
        super();
        int row = Integer.MAX_VALUE,column = Integer.MAX_VALUE,maxRow = 0,maxColumn = 0;
        for (int i = 0,isize = dataOperators.size();i < isize;i++){
            DataOperator data = dataOperators.get(i);
            row  = Math.min(data.getRange().getRow(),row);
            column  = Math.min(data.getRange().getColumn(),column);
            maxRow = Math.max(data.getRange().getLastRow(),maxRow);
            maxColumn = Math.max(data.getRange().getLastColumn(),maxColumn);
        }
        _range = new RangeImpl(dataOperators.get(0).getRange().getSheet(),row,column,maxRow,maxColumn);
        for (int i = 0,isize = dataOperators.size();i < isize;i++) {
            DataOperator data = dataOperators.get(i);

        }
    }

    @Override
    public void evaluate(FormulaExecutor context) {

    }

    @Override
    public void merge(DataOperator dataOperator) throws OptimizationError {
        throw new OptimizationError("Unspport functionality");
    }
}
