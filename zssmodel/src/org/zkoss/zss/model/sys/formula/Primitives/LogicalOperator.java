package org.zkoss.zss.model.sys.formula.Primitives;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

public class LogicalOperator {
    private List<Edge> inEdges, outEdges;

    LogicalOperator(){
        inEdges = new ArrayList<>();
        outEdges = new ArrayList<>();
    }

    public static void connect(LogicalOperator in, LogicalOperator out){
        Edge edge = new Edge(in,out);
        in.addOutput(edge);
        out.addInput(edge);
    }

    private void addInput(Edge op){
        inEdges.add(op);
    }

    private void addOutput(Edge op){
        outEdges.add(op);
    }

    void transferInEdge(Edge e){
        inEdges.add(e);
        e.setOutVertex(this);
    }

    void transferOutEdge(Edge e){
        outEdges.add(e);
        e.setInVertex(this);
    }

    void transferInEdges(List<Edge> edges){
        for (Edge e:edges)
            transferInEdge(e);
    }

    void transferOutEdges(List<Edge> edges){
        for (Edge e:edges)
            transferOutEdge(e);
    }

    List<Edge> getInEdges(){
        return inEdges;
    }

    List<Edge> getOutEdges(){
        return outEdges;
    }

    public Iterator<LogicalOperator> getOutputNodes(){
        return new Iterator<LogicalOperator>() {
            int i = -1;
            @Override
            public boolean hasNext() {
                return i < outEdges.size() - 1;
            }

            @Override
            public LogicalOperator next() {
                i += 1;
                return outEdges.get(i).getOutVertex();
            }
        };
    }
}
