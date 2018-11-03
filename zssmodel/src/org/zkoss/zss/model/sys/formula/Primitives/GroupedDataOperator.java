package org.zkoss.zss.model.sys.formula.Primitives;

import javafx.util.Pair;
import org.zkoss.poi.ss.formula.eval.NumberEval;
import org.zkoss.poi.ss.formula.eval.ValueEval;
import org.zkoss.zss.model.CellRegion;
import org.zkoss.zss.model.SCell;
import org.zkoss.zss.model.impl.AbstractCellAdv;
import org.zkoss.zss.model.sys.formula.Exception.OptimizationError;
import org.zkoss.zss.model.sys.formula.QueryOptimization.FormulaExecutor;

import java.util.*;

public class GroupedDataOperator extends DataOperator{

    private List<Pair<Integer, Integer>> inEdgesRange = new ArrayList<>(), outEdgesRange = new ArrayList<>();

    private Pair<Integer, Integer> getIndexRange(CellRegion region){
        int left = (region.getRow() - _region.getRow()) * _region.getColumnCount();
        int right = (region.getLastRow() - _region.getRow() + 1) * _region.getColumnCount();
        return new Pair<>(left,right);
    }

    private int getIndex(SCell cell){
        return  (cell.getRowIndex() - _region.getRow()) * _region.getColumnCount() +
                cell.getColumnIndex() - _region.getColumn();
    }

    public GroupedDataOperator(List<DataOperator> dataOperators){
        super();
        int row = Integer.MAX_VALUE,column = Integer.MAX_VALUE,maxRow = 0,maxColumn = 0;
        for (int i = 0,isize = dataOperators.size();i < isize;i++){
            DataOperator data = dataOperators.get(i);
            row  = Math.min(data.getRegion().getRow(),row);
            column  = Math.min(data.getRegion().getColumn(),column);
            maxRow = Math.max(data.getRegion().getLastRow(),maxRow);
            maxColumn = Math.max(data.getRegion().getLastColumn(),maxColumn);
        }
        _region = new CellRegion(row,column,maxRow,maxColumn);
        _sheet = dataOperators.get(0).getSheet();
        for (int i = 0,isize = dataOperators.size();i < isize;i++) {
            DataOperator data = dataOperators.get(i);
            int current = inDegree();
            data.forEachInEdge(this::transferInEdge);
            for (int insize = inDegree();current < insize;current++)
                inEdgesRange.add(getIndexRange(data.getRegion()));
            current = outDegree();
            data.forEachOutEdge(this::transferOutEdge);
            for (int outsize = outDegree();current < outsize;current++)
                outEdgesRange.add(getIndexRange(data.getRegion()));
        }
    }

    @Override
    public void evaluate(FormulaExecutor context) throws OptimizationError {
        for (Edge e: getInEdges())
            if (!e.resultIsReady())
                return;

        Object[] data = new Object[_region.getCellCount()];
        Collection<SCell> cells = _sheet.getCells(_region);

        int inEdgeCursor = 0;

        Pair<Integer, Integer> currentRange = null;
        Iterator currentResultIterator = null;
        if (inEdgeCursor < inEdgesRange.size()){
            currentRange = inEdgesRange.get(inEdgeCursor);
            currentResultIterator = getInEdges().get(inEdgeCursor).popResult().iterator();
        }

        for (SCell cell : cells){
            if (cell.getType() != SCell.CellType.NUMBER)
                throw OptimizationError.UNSUPPORTED_TYPE;
            int i = getIndex(cell);
            if (inEdgeCursor < inEdgesRange.size() && i >= currentRange.getValue()) {
                inEdgeCursor++;
                if (inEdgeCursor < inEdgesRange.size()){
                    currentRange = inEdgesRange.get(inEdgeCursor);
                    currentResultIterator = getInEdges().get(inEdgeCursor).popResult().iterator();
                }
            }
            if (inEdgeCursor < inEdgesRange.size() && i >= currentRange.getKey()){
                Object value = currentResultIterator.next();
                ValueEval resultValue;
                if (value instanceof Double){
                    resultValue = new NumberEval((Double)value);
                }
                else
                    throw OptimizationError.UNSUPPORTED_TYPE;
                ((AbstractCellAdv)cell).setFormulaResultValue(resultValue);
                context.update(_sheet, (AbstractCellAdv) cell);
                data[i] = value;
            }
            else
                data[i] = cell.getValue();
        }
        List results = Arrays.asList(data);
        for (int i =0,isize = outDegree(); i < isize;i++){
            getOutEdges().get(i).setResult(results.subList(outEdgesRange.get(i).getKey(),outEdgesRange.get(i).getValue()));
        }
        _evaluated = true;
    }

    @Override
    public void merge(DataOperator dataOperator) throws OptimizationError {
        throw OptimizationError.UNSUPPORTED_FUNCTION;
    }
}
