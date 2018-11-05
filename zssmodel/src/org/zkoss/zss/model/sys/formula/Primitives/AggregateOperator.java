package org.zkoss.zss.model.sys.formula.Primitives;

import org.zkoss.zss.model.sys.formula.QueryOptimization.FormulaExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AggregateOperator extends PhysicalOperator {
    private BinaryFunction binaryFunction;
    public AggregateOperator(BinaryFunction f){
        super();
        binaryFunction = f;
    }

    @Override
    public void evaluate(FormulaExecutor context) {
        List<Double> data = (List<Double>)getInEdge(0).popResult();
        List<Double> result = Collections.singletonList(binaryFunction.groupEvaluate(data));

        forEachOutEdge((o)-> o.setResult(result));

    }

}