package org.zkoss.zss.model.sys.formula.Primitives;

import org.zkoss.poi.ss.formula.ptg.AreaPtg;

public class DataReadOperator extends LogicalOperator {
    AreaPtg area;
    public DataReadOperator(AreaPtg area){
        super();
        this.area = area;
    }
}
