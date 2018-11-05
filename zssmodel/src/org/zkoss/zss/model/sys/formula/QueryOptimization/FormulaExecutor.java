package org.zkoss.zss.model.sys.formula.QueryOptimization;

import javafx.util.Pair;
import org.zkoss.zss.model.SSheet;
import org.zkoss.zss.model.impl.AbstractCellAdv;
import org.zkoss.zss.model.impl.RefImpl;
import org.zkoss.zss.model.sys.dependency.Ref;
import org.zkoss.zss.model.sys.formula.Exception.OptimizationError;
import org.zkoss.zss.model.sys.formula.FormulaAsyncScheduler;
import org.zkoss.zss.model.sys.formula.Primitives.LogicalOperator;
import org.zkoss.zss.model.sys.formula.Primitives.PhysicalOperator;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.zkoss.zss.model.sys.formula.Test.Timer.time;

public class FormulaExecutor {
    static FormulaExecutor uniqueExecutor = new FormulaExecutor();
    FormulaAsyncScheduler scheduler = null;
    Thread frontEndUpdateThread;
    LinkedBlockingQueue<Pair<SSheet, AbstractCellAdv>> frontEndUpdateQueue;
    private FormulaExecutor(){
        frontEndUpdateQueue = new LinkedBlockingQueue<>();
        frontEndUpdateThread = new Thread(()->{
            try {
                while (true){
                    Pair<SSheet, AbstractCellAdv> parameter = frontEndUpdateQueue.take();
                    update(parameter.getKey(),parameter.getValue());
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        frontEndUpdateThread.start();
    }
    public static FormulaExecutor getExecutor(){
        return uniqueExecutor;
    }
    public void execute(QueryPlanGraph graph, FormulaAsyncScheduler scheduler) throws OptimizationError {
        this.scheduler = scheduler;
        synchronized (graph){
            for (LogicalOperator op:graph.dataNodes)
                recursiveEvaluate(op);
        }

    }

    private void evaluate(PhysicalOperator operator){
        time(operator.getClass().getSimpleName(), ()-> {
            try {
                operator.evaluate(this);
            } catch (OptimizationError optimizationError) {
                optimizationError.printStackTrace();
            }
        });
    }

    public void addToUpdateQueue(SSheet sheet, AbstractCellAdv sCell){
        frontEndUpdateQueue.offer(new Pair<>(sheet,sCell));
    }

    private void update(SSheet sheet, AbstractCellAdv sCell){
        Map<String, int[]> visibleRange = FormulaAsyncScheduler.getVisibleMap().get(sheet);
        boolean hasOverlap = false;
        if (visibleRange != null && !visibleRange.isEmpty()) {
            for (int[] rows : visibleRange.values()) {
                Ref overlap = sCell.getRef()
                        .getOverlap(new RefImpl(null, null,
                                rows[0], 0, rows[1], Integer.MAX_VALUE));
                if (overlap != null){
                    hasOverlap = true;
                    break;
                }
            }
        }
        if (!hasOverlap)
            return;
        scheduler.update(sheet.getBook(), sheet, sCell.getCellRegion(),
                sCell.getValue(true, false).toString(),
                sCell.getFormulaValue());
    }

    private void recursiveEvaluate(LogicalOperator op) throws OptimizationError {
        if (!(op instanceof PhysicalOperator))
            throw new OptimizationError("Logical Operator not converted");
        PhysicalOperator p = (PhysicalOperator)op;
        if (!p.readyToEvaluate())
            return;

        evaluate(p);
        for (Iterator<LogicalOperator> it = p.getOutputNodes();it.hasNext();)
            recursiveEvaluate(it.next());
    }
}
