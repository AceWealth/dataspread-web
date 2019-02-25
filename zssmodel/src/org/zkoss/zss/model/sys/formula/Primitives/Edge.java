package org.zkoss.zss.model.sys.formula.Primitives;

import org.zkoss.zss.model.sys.formula.DataStructure.Range;

import java.util.List;

class Edge {
    private LogicalOperator in,out;

    private boolean valid = true;

    private List result = null;

    Range inRange,outRange;

    private int tag;

    Edge(LogicalOperator in, LogicalOperator out,int tag){
        this.in = in;
        this.out = out;
        this.tag = tag;
    }

    void remove(){
        valid = false;
        in.removeOutEdge();
        out.removeInEdge();
    }

    boolean isValid(){
        return valid;
    }


    LogicalOperator getInVertex(){
        return in;
    }

    LogicalOperator getOutVertex(){
        return out;
    }

    void setInVertex(LogicalOperator op){
        in = op;
    }

    void setOutVertex(LogicalOperator op){
        out = op;
    }

    void setResult(List result){
        if (inRange == null)
            this.result = result;
        else
            this.result = result.subList(inRange.left,inRange.right);
        ((PhysicalOperator)out).incInputCount();
    }

    List popResult(){
        List ret = result;
        result = null;
        ((PhysicalOperator)out).decInputCount();
        return ret;
    }

    void setTag(int t){
        tag = t;
    }

    int getTag(){
        return tag;
    }

}
