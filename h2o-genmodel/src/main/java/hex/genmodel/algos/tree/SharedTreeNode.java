package hex.genmodel.algos.tree;

import ai.h2o.algos.tree.INode;
import ai.h2o.algos.tree.INodeStat;
import hex.genmodel.tools.PrintMojo;
import hex.genmodel.utils.GenmodelBitSet;

import java.io.PrintStream;
import java.util.*;

/**
 * Node in a tree.
 * A node (optionally) contains left and right edges to the left and right child nodes.
 */
public class SharedTreeNode implements INode<double[]>, INodeStat {
  final int internalId; // internal tree id (that links the node back to the array of nodes of the tree) - don't confuse with user-facing nodeNumber!
  final SharedTreeNode parent;
  final int subgraphNumber;
  int nodeNumber;
  float weight;
  final int depth;
  int colId;
  String colName;
  boolean leftward;
  boolean naVsRest;
  float splitValue = Float.NaN;
  String[] domainValues;
  GenmodelBitSet bs;
  float predValue = Float.NaN;
  float squaredError = Float.NaN;
  SharedTreeNode leftChild;
  public SharedTreeNode rightChild;

  // Whether NA for this colId is reachable to this node.
  private boolean inclusiveNa;

  // When a column is categorical, levels that are reachable to this node.
  // This in particular includes any earlier splits of the same colId.
  private BitSet inclusiveLevels;

  /**
   * Create a new node.
   * @param p Parent node
   * @param sn Tree number
   * @param d Node depth within the tree
   */
  SharedTreeNode(int id, SharedTreeNode p, int sn, int d) {
    internalId = id;
    parent = p;
    subgraphNumber = sn;
    depth = d;
  }

  public int getDepth() {
    return depth;
  }

  public int getNodeNumber() {
    return nodeNumber;
  }

  @Override
  public float getWeight() {
    return weight;
  }

  public void setNodeNumber(int id) {
    nodeNumber = id;
  }

  void setWeight(float w) {
    weight = w;
  }

  public void setCol(int v1, String v2) {
    colId = v1;
    colName = v2;
  }

  public int getColId() {
    return colId;
  }

  public void setLeftward(boolean v) {
    leftward = v;
  }

  void setNaVsRest(boolean v) {
    naVsRest = v;
  }

  public void setSplitValue(float v) {
    splitValue = v;
  }

  public void setColName(String colName) {
    this.colName = colName;
  }

  void setBitset(String[] v1, GenmodelBitSet v2) {
    assert (v1 != null);
    domainValues = v1;
    bs = v2;
  }

  public void setPredValue(float v) {
    predValue = v;
  }

  public void setSquaredError(float v) {
    squaredError = v;
  }

  /**
   * Calculate whether the NA value for a particular colId can reach this node.
   * @param colIdToFind Column id to find
   * @return true if NA of colId reaches this node, false otherwise
   */
  private boolean findInclusiveNa(int colIdToFind) {
    if (parent == null) {
      return true;
    }
    else if (parent.getColId() == colIdToFind) {
      return inclusiveNa;
    }
    return parent.findInclusiveNa(colIdToFind);
  }

  private boolean calculateChildInclusiveNa(boolean includeThisSplitEdge) {
    return findInclusiveNa(colId) && includeThisSplitEdge;
  }

  /**
   * Find the set of levels for a particular categorical column that can reach this node.
   * A null return value implies the full set (i.e. every level).
   * @param colIdToFind Column id to find
   * @return Set of levels
   */
  private BitSet findInclusiveLevels(int colIdToFind) {
    if (parent == null) {
      return null;
    }
    if (parent.getColId() == colIdToFind) {
      return inclusiveLevels;
    }
    return parent.findInclusiveLevels(colIdToFind);
  }

  private boolean calculateIncludeThisLevel(BitSet inheritedInclusiveLevels, int i) {
    if (inheritedInclusiveLevels == null) {
      // If there is no prior split history for this column, then treat the
      // inherited set as complete.
      return true;
    }
    else if (inheritedInclusiveLevels.get(i)) {
      // Allow levels that flowed into this node.
      return true;
    }

    // Filter out levels that were already discarded from a previous split.
    return false;
  }

  /**
   * Calculate the set of levels that flow through to a child.
   * @param includeAllLevels naVsRest dictates include all (inherited) levels
   * @param discardAllLevels naVsRest dictates discard all levels
   * @param nodeBitsetDoesContain true if the GenmodelBitset from the compressed_tree
   * @return Calculated set of levels
   */
  private BitSet calculateChildInclusiveLevels(boolean includeAllLevels,
                                               boolean discardAllLevels,
                                               boolean nodeBitsetDoesContain) {
    BitSet inheritedInclusiveLevels = findInclusiveLevels(colId);
    BitSet childInclusiveLevels = new BitSet();


    for (int i = 0; i < domainValues.length; i++) {
      // Calculate whether this level should flow into this child node.
      boolean includeThisLevel = false;
      {
        if (discardAllLevels) {
          includeThisLevel = false;
        } else if (includeAllLevels) {
          includeThisLevel = calculateIncludeThisLevel(inheritedInclusiveLevels, i);
        } else if (!Float.isNaN(splitValue)) {
          // This branch is only used if categorical split is represented numerically
          includeThisLevel = splitValue < i ^ !nodeBitsetDoesContain;
        } else if (bs.isInRange(i) && bs.contains(i) == nodeBitsetDoesContain) {
          includeThisLevel = calculateIncludeThisLevel(inheritedInclusiveLevels, i);
        }
      }

      if (includeThisLevel) {
        childInclusiveLevels.set(i);
      }
    }
    return childInclusiveLevels;
  }

  void setLeftChild(SharedTreeNode v) {
    leftChild = v;

    boolean childInclusiveNa = calculateChildInclusiveNa(leftward);
    v.setInclusiveNa(childInclusiveNa);

    if (! isBitset()) {
      return;
    }
    BitSet childInclusiveLevels = calculateChildInclusiveLevels(naVsRest, false, false);
    v.setInclusiveLevels(childInclusiveLevels);
  }

  void setRightChild(SharedTreeNode v) {
    rightChild = v;

    boolean childInclusiveNa = calculateChildInclusiveNa(!leftward);
    v.setInclusiveNa(childInclusiveNa);

    if (! isBitset()) {
      return;
    }
    BitSet childInclusiveLevels = calculateChildInclusiveLevels(false, naVsRest, true);
    v.setInclusiveLevels(childInclusiveLevels);
  }

  public void setInclusiveNa(boolean v) {
    inclusiveNa = v;
  }

  public boolean getInclusiveNa() {
    return inclusiveNa;
  }

  private void setInclusiveLevels(BitSet v) {
    inclusiveLevels = v;
  }

  public BitSet getInclusiveLevels() {
    return inclusiveLevels;
  }

  public String getName() {
    return "Node " + nodeNumber;
  }

  public void print() {
    print(System.out, null);
  }

  public void print(PrintStream out, String description) {
    out.println("        Node " + nodeNumber + (description != null ? " (" + description + ")" : ""));
    out.println("            weight:      " + weight);
    out.println("            depth:       " + depth);
    out.println("            colId:       " + colId);
    out.println("            colName:     " + ((colName != null) ? colName : ""));
    out.println("            leftward:    " + leftward);
    out.println("            naVsRest:    " + naVsRest);
    out.println("            splitVal:    " + splitValue);
    out.println("            isBitset:    " + isBitset());
    out.println("            predValue:   " + predValue);
    out.println("            squaredErr:  " + squaredError);
    out.println("            leftChild:   " + ((leftChild != null) ? leftChild.getName() : ""));
    out.println("            rightChild:  " + ((rightChild != null) ? rightChild.getName() : ""));
  }

  void printEdges() {
    if (leftChild != null) {
      System.out.println("        " + getName() + " ---left---> " + leftChild.getName());
      leftChild.printEdges();
    }
    if (rightChild != null) {
      System.out.println("        " + getName() + " ---right--> " + rightChild.getName());
      rightChild.printEdges();
    }
  }

  private String getDotName() {
    return "SG_" + subgraphNumber + "_Node_" + nodeNumber;
  }

  public boolean isBitset() {
    return (domainValues != null);
  }

  public static String escapeQuotes(String s) {
    return s.replace("\"", "\\\"");
  }

  private void printDotNode(PrintStream os, boolean detail, PrintMojo.PrintTreeOptions treeOptions) {
    os.print("\"" + getDotName() + "\"");
    os.print(" [");

    if (leftChild==null && rightChild==null) {
      os.print("fontsize="+treeOptions._fontSize+", label=\"");
      float predv = treeOptions._setDecimalPlace?treeOptions.roundNPlace(predValue):predValue;
      os.print(predv);
    } else if (isBitset() && (Float.isNaN(splitValue) || !treeOptions._internal)) {
      os.print("shape=box, fontsize="+treeOptions._fontSize+", label=\"");
      os.print(escapeQuotes(colName));
    }
    else {
      assert(! Float.isNaN(splitValue));
      float splitV = treeOptions._setDecimalPlace?treeOptions.roundNPlace(splitValue):splitValue;
      os.print("shape=box, fontsize="+treeOptions._fontSize+", label=\"");
      os.print(escapeQuotes(colName) + " < " + splitV);
    }

    if (detail) {
      os.print("\\n\\nN" + getNodeNumber() + "\\n");
      if (leftChild != null || rightChild != null) {
        if (!Float.isNaN(predValue)) {
          float predv = treeOptions._setDecimalPlace?treeOptions.roundNPlace(predValue):predValue;
          os.print("\\nPred: " + predv);
        }
      }
      if (!Float.isNaN(squaredError)) {
        os.print("\\nSE: " + squaredError);
      }
      os.print("\\nW: " + getWeight());
      if (naVsRest) {
        os.print("\\n" + "nasVsRest");
      }
      if (leftChild != null) {
        os.print("\\n" + "L: N" + leftChild.getNodeNumber());
      }
      if (rightChild != null) {
        os.print("\\n" + "R: N" + rightChild.getNodeNumber());
      }
    }

    os.print("\"]");
    os.println("");
  }

  /**
   * Recursively print nodes at a particular depth level in the tree.  Useful to group them so they render properly.
   * @param os output stream
   * @param levelToPrint level number
   * @param detail include additional node detail information
   */
  void printDotNodesAtLevel(PrintStream os, int levelToPrint, boolean detail, PrintMojo.PrintTreeOptions treeOptions) {
    if (getDepth() == levelToPrint) {
      printDotNode(os, detail, treeOptions);
      return;
    }

    assert (getDepth() < levelToPrint);

    if (leftChild != null) {
      leftChild.printDotNodesAtLevel(os, levelToPrint, detail, treeOptions);
    }
    if (rightChild != null) {
      rightChild.printDotNodesAtLevel(os, levelToPrint, detail, treeOptions);
    }
  }

  private void printDotEdgesCommon(PrintStream os, int maxLevelsToPrintPerEdge, ArrayList<String> arr,
                                   SharedTreeNode child, float totalWeight, boolean detail,
                                   PrintMojo.PrintTreeOptions treeOptions) {
    if (isBitset() || (!Float.isNaN(splitValue) && treeOptions._internal)) { // Print categorical levels even in case of internal numerical representation
      BitSet childInclusiveLevels = child.getInclusiveLevels();
      int total = childInclusiveLevels.cardinality();
      if ((total > 0) && (total <= maxLevelsToPrintPerEdge)) {
        for (int i = childInclusiveLevels.nextSetBit(0); i >= 0; i = childInclusiveLevels.nextSetBit(i+1)) {
          arr.add(domainValues[i]);
        }
      }
      else {
        arr.add(total + " levels");
      }
    }

    if (detail) {
      try {
        final int max_width = 15 - 1;
        float width = child.getWeight() / totalWeight * max_width;
        int intWidth = Math.round(width) + 1;
        os.print("penwidth=");
        os.print(intWidth);
        os.print(",");
      } catch (Exception ignore) {
      }
    }

    os.print("fontsize="+treeOptions._fontSize+", label=\"");
    for (String s : arr) {
      os.print(escapeQuotes(s) + "\\n");
    }
    os.print("\"");
    os.println("]");
  }

  /**
   * Recursively print all edges in the tree.
   * @param os output stream
   * @param maxLevelsToPrintPerEdge Limit the number of individual categorical level names printed per edge
   * @param totalWeight total weight of all observations (used to determine edge thickness)
   * @param detail include additional edge detail information
   */
  void printDotEdges(PrintStream os, int maxLevelsToPrintPerEdge, float totalWeight, boolean detail,
                     PrintMojo.PrintTreeOptions treeOptions) {
    assert (leftChild == null) == (rightChild == null);

    if (leftChild != null) {
      os.print("\"" + getDotName() + "\"" + " -> " + "\"" + leftChild.getDotName() + "\"" + " [");

      ArrayList<String> arr = new ArrayList<>();
      if (leftChild.getInclusiveNa()) {
        arr.add("[NA]");
      }

      if (naVsRest) {
        arr.add("[Not NA]");
      }
      else {
        if (!isBitset() || (!Float.isNaN(splitValue) && treeOptions._internal)) {
          arr.add("<");
        }
      }

      printDotEdgesCommon(os, maxLevelsToPrintPerEdge, arr, leftChild, totalWeight, detail, treeOptions);
    }

    if (rightChild != null) {
      os.print("\"" + getDotName() + "\"" + " -> " + "\"" + rightChild.getDotName() + "\"" + " [");

      ArrayList<String> arr = new ArrayList<>();
      if (rightChild.getInclusiveNa()) {
        arr.add("[NA]");
      }

      if (! naVsRest) {
        if (!isBitset() || (!Float.isNaN(splitValue) && treeOptions._internal)) {
          arr.add(">=");
        }
      }

      printDotEdgesCommon(os, maxLevelsToPrintPerEdge, arr, rightChild, totalWeight, detail, treeOptions);
    }
  }

  public Map<String, Object> toJson() {
    Map<String, Object> json = new HashMap<>();
    json.put("nodeNumber", nodeNumber);
    if (!Float.isNaN(weight)) json.put("weight", weight);
    json.put("depth", depth);
    json.put("colId", colId);
    json.put("colName", colName);
    json.put("leftward", leftward);
    json.put("naVsRest", naVsRest);
    json.put("inclusiveNa", inclusiveNa);
    json.put("isCategorical", isBitset());
    if (!Float.isNaN(predValue)) json.put("predValue", predValue);
    if (!Float.isNaN(squaredError)) json.put("squaredError", squaredError);
    if (domainValues != null && bs != null) {
      List<String> matchedDomainValues = new ArrayList<>();
      for (int i = inclusiveLevels.nextSetBit(0); i >= 0; i = inclusiveLevels.nextSetBit(i+1)) {
        matchedDomainValues.add(domainValues[i]);
      }
      json.put("matchValues", matchedDomainValues);
    } else if (!Float.isNaN(splitValue)) {
      json.put("splitValue", splitValue);
    }
    if (leftChild != null) {
      json.put("leftChild", leftChild.toJson());
    }
    if (rightChild != null) {
      json.put("rightChild", rightChild.toJson());
    }
    return json;
  }
  
  public SharedTreeNode getParent() {
    return parent;
  }

  public int getSubgraphNumber() {
    return subgraphNumber;
  }

  public String getColName() {
    return colName;
  }

  public boolean isLeftward() {
    return leftward;
  }

  public boolean isNaVsRest() {
    return naVsRest;
  }

  public float getSplitValue() {
    return splitValue;
  }

  public String[] getDomainValues() {
    return domainValues;
  }

  public void setDomainValues(String[] domainValues) {
    this.domainValues = domainValues;
  }

  public GenmodelBitSet getBs() {
    return bs;
  }

  public float getPredValue() {
    return predValue;
  }

  public float getSquaredError() {
    return squaredError;
  }

  public SharedTreeNode getLeftChild() {
    return leftChild;
  }

  public SharedTreeNode getRightChild() {
    return rightChild;
  }

  public boolean isInclusiveNa() {
    return inclusiveNa;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SharedTreeNode that = (SharedTreeNode) o;
    return subgraphNumber == that.subgraphNumber &&
            nodeNumber == that.nodeNumber &&
            Float.compare(that.weight, weight) == 0 &&
            depth == that.depth &&
            colId == that.colId &&
            leftward == that.leftward &&
            naVsRest == that.naVsRest &&
            Float.compare(that.splitValue, splitValue) == 0 &&
            Float.compare(that.predValue, predValue) == 0 &&
            Float.compare(that.squaredError, squaredError) == 0 &&
            inclusiveNa == that.inclusiveNa &&
            Objects.equals(colName, that.colName) &&
            Arrays.equals(domainValues, that.domainValues) &&
            Objects.equals(leftChild, that.leftChild) &&
            Objects.equals(rightChild, that.rightChild) &&
            Objects.equals(inclusiveLevels, that.inclusiveLevels);
  }

  @Override
  public int hashCode() {

    return Objects.hash(subgraphNumber, nodeNumber);
  }

  // This is the generic Node API (needed by TreeSHAP)
  
  @Override
  public final boolean isLeaf() {
    return leftChild == null && rightChild == null;
  }

  @Override
  public final float getLeafValue() {
    return predValue;
  }

  @Override
  public final int getSplitIndex() {
    return colId;
  }

  @Override
  public final int next(double[] value) {
    final double d = value[colId];

    if (
            Double.isNaN(d) || 
            (bs != null && !bs.isInRange((int)d)) || 
            (domainValues != null && domainValues.length <= (int) d)
        ? 
            !leftward 
        : 
            !naVsRest && (bs == null ? d >= splitValue : bs.contains((int)d))
    ) {
      // go RIGHT
      return getRightChildIndex();
    }
    
    if (Double.isNaN(d) || 
            (bs != null && !bs.isInRange((int)d)) || (domainValues != null && domainValues.length <= (int)d)
            ? !leftward : !naVsRest && (bs == null ? d >= splitValue : bs.contains((int)d))) {
      // go RIGHT
      return getRightChildIndex();
    } else {
      // go LEFT
      return getLeftChildIndex();
    }
  }

  @Override
  public final int getLeftChildIndex() {
    return leftChild != null ? leftChild.internalId : -1;
  }

  @Override
  public final int getRightChildIndex() {
    return rightChild != null ? rightChild.internalId : -1;
  }

}
