package org.zkoss.zss.model.sys.formula.Primitives;

import org.zkoss.poi.ss.formula.eval.NumberEval;
import org.zkoss.poi.ss.formula.eval.ValueEval;
import org.zkoss.zss.model.CellRegion;
import org.zkoss.zss.model.SCell;
import org.zkoss.zss.model.SSheet;
import org.zkoss.zss.model.impl.AbstractCellAdv;
import org.zkoss.zss.model.sys.formula.Exception.OptimizationError;
import org.zkoss.zss.model.sys.formula.QueryOptimization.FormulaExecutor;
import org.zkoss.zss.range.SRange;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SingleDataOperator extends DataOperator{
    public SingleDataOperator(SSheet sheet, CellRegion region){
        super(sheet, region);
    }

    @Override
    public void evaluate(FormulaExecutor context) throws OptimizationError {
        List results;
        if (inDegree() == 0){
            results = new ArrayList<>();

            for (SCell cell : _sheet.getCells(_region)){
                if (cell.getType() != SCell.CellType.NUMBER && cell.getType() != SCell.CellType.FORMULA)
                    throw OptimizationError.UNSUPPORTED_TYPE;
                results.add(cell.getValue());
            }
        }
        else{
            results = getInEdges().get(0).popResult();
            Iterator it= results.iterator();
            for (int i = _region.getRow(); i <= _region.getLastRow(); i++)
                for (int j = _region.getColumn(); j <= _region.getLastColumn(); j++) {
                    Object result = it.next();
                    setFormulaValue(i,j,result,context);
                }
        }
        for (Edge o:getOutEdges()){
            o.setResult(results);
        }
    }

    @Override
    public void merge(DataOperator dataOperator) throws OptimizationError {
        if (!(dataOperator instanceof SingleDataOperator))
            throw OptimizationError.UNSUPPORTED_FUNCTION;
        dataOperator.forEachInEdge(this::transferInEdge);
        dataOperator.forEachOutEdge(this::transferOutEdge);
    }

    private void setFormulaValue(int row, int column, Object result,FormulaExecutor context) throws OptimizationError {
        ValueEval resultValue;
        if (result instanceof Double){
            resultValue = new NumberEval((Double)result);
        }
        else
            throw OptimizationError.UNSUPPORTED_TYPE;
        AbstractCellAdv sCell = ((AbstractCellAdv)_sheet.getCell(row,column));
        sCell.setFormulaResultValue(resultValue);
        context.update(_sheet,sCell);
    }
}
