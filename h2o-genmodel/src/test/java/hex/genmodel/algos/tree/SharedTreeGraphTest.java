package hex.genmodel.algos.tree;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class SharedTreeGraphTest {

    @SuppressWarnings("unchecked")
    @Test
    public void toJson() {
        SharedTreeGraph g = new SharedTreeGraph();
        SharedTreeSubgraph t1 = g.makeSubgraph("tree_1");
        SharedTreeNode r1 = t1.makeRootNode();
        r1.setSplitValue(1f);
        r1.setCol(3, "c3");
        r1.setInclusiveNa(false);
        r1.setNodeNumber(0);

        SharedTreeNode c1 = t1.makeLeftChildNode(r1);
        c1.setPredValue(123.456f);
        c1.setCol(1, "c1");
        c1.setInclusiveNa(true);
        c1.setNodeNumber(1);

        SharedTreeNode c2 = t1.makeRightChildNode(r1);
        c2.setPredValue(.456f);
        c2.setCol(2, "c2");
        c2.setInclusiveNa(true);
        c2.setNodeNumber(2);
        
        Map json = g.toJson();

        Map c1Json = new HashMap();
        c1Json.put("isCategorical", false);
        c1Json.put("colName", "c1");
        c1Json.put("depth", 1);
        c1Json.put("nodeNumber", 1);
        c1Json.put("weight", 0f);
        c1Json.put("inclusiveNa", true);
        c1Json.put("predValue", 123.456f);
        c1Json.put("colId", 1);
        c1Json.put("naVsRest", false);
        c1Json.put("leftward", false);
        
        Map c2Json = new HashMap();
        c2Json.put("isCategorical", false);
        c2Json.put("colName", "c2");
        c2Json.put("depth", 1);
        c2Json.put("nodeNumber", 2);
        c2Json.put("weight", 0f);
        c2Json.put("inclusiveNa", true);
        c2Json.put("predValue", .456f);
        c2Json.put("colId", 2);
        c2Json.put("naVsRest", false);
        c2Json.put("leftward", false);

        Map root1Json = new HashMap();
        root1Json.put("isCategorical", false);
        root1Json.put("splitValue", 1f);
        root1Json.put("colName", "c3");
        root1Json.put("nodeNumber", 0);
        root1Json.put("weight", 0f);
        root1Json.put("depth", 0);
        root1Json.put("inclusiveNa", false);
        root1Json.put("colId", 3);
        root1Json.put("naVsRest", false);
        root1Json.put("leftward", false);
        root1Json.put("leftChild", c1Json);
        root1Json.put("rightChild", c2Json);

        Map t1Json = new HashMap();
        t1Json.put("root", root1Json);
        t1Json.put("name", "tree_1");
        t1Json.put("index", 0);

        List<Map> treesJson = Collections.singletonList(t1Json);
        
        Map expected = new HashMap();
        expected.put("trees", treesJson);
        
        assertEquals(expected, json);
    }
}
