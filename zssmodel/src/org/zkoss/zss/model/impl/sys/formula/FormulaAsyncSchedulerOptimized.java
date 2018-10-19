package org.zkoss.zss.model.impl.sys.formula;

import org.zkoss.zss.model.CellRegion;
import org.zkoss.zss.model.SCell;
import org.zkoss.zss.model.SSheet;
import org.zkoss.zss.model.impl.AbstractCellAdv;
import org.zkoss.zss.model.sys.BookBindings;
import org.zkoss.zss.model.sys.formula.DirtyManager;
import org.zkoss.zss.model.sys.formula.DirtyManagerLog;
import org.zkoss.zss.model.sys.formula.Exception.OptimizationError;
import org.zkoss.zss.model.sys.formula.FormulaAsyncScheduler;
import org.zkoss.zss.model.sys.formula.FormulaExpression;
import org.zkoss.zss.model.sys.formula.QueryOptimization.FormulaExecutor;
import org.zkoss.zss.model.sys.formula.QueryOptimization.QueryOptimizer;
import org.zkoss.zss.model.sys.formula.QueryOptimization.QueryPlanGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static org.zkoss.zss.model.sys.formula.Decomposer.FormulaDecomposer.decomposeFormula;

/**
 * Execute formulae in a single thread.
 */
public class FormulaAsyncSchedulerOptimized extends FormulaAsyncScheduler {
    private static final Logger logger = Logger.getLogger(FormulaAsyncSchedulerOptimized.class.getName());
    private boolean keepRunning = true;

    @Override
    public void run() {
        while (keepRunning) {
            List<DirtyManager.DirtyRecord> dirtyRecordSet = DirtyManager.dirtyManagerInstance.getAllDirtyRegions();

            List<SCell> computedCells = new ArrayList<>();

            // Compute the remaining
            ArrayList<QueryPlanGraph> graphs = new ArrayList<>();
            for (DirtyManager.DirtyRecord dirtyRecord : dirtyRecordSet) {
                //logger.info("Processing " + dirtyRecord.region);
                SSheet sheet = BookBindings.getSheetByRef(dirtyRecord.region);
                Collection<SCell> cells = sheet.getCells(new CellRegion(dirtyRecord.region));
                for (SCell sCell : cells) {
                    if (computedCells.contains(sCell))
                        continue;
                    if (sCell.getType() == SCell.CellType.FORMULA) {
//                        FormulaComputationStatusManager.getInstance().updateFormulaCell(
//                                sCell.getRowIndex(),
//                                sCell.getColumnIndex(),
//                                sCell, sheet, 10);
                        DirtyManagerLog.instance.markClean(sCell.getCellRegion());
                        try {
                            graphs.add(decomposeFormula(((FormulaExpression) ((AbstractCellAdv) sCell)
                                    .getValue(false)).getPtgs(),sCell));
                        } catch (OptimizationError optimizationError) {
                            optimizationError.printStackTrace();
                        }

                    }
//                    FormulaComputationStatusManager.getInstance().doneComputation();
                }

                DirtyManager.dirtyManagerInstance.removeDirtyRegion(dirtyRecord.region,
                        dirtyRecord.trxId);
            }
            if (graphs.size() == 0)
                continue;
            QueryPlanGraph optimizedGraph = QueryOptimizer.getOptimizer().optimize(graphs);
            try {
                FormulaExecutor.getExecutor().execute(optimizedGraph,this);
                optimizedGraph.clean();
            } catch (OptimizationError optimizationError) {
                optimizationError.printStackTrace();
            }

            //logger.info("Done computing " + dirtyRecord.region );
        }
    }

    @Override
    public void shutdown() {
        keepRunning = false;
    }


}
