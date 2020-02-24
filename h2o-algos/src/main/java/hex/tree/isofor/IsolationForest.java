package hex.tree.isofor;

import hex.ModelCategory;
import hex.ScoreKeeper;
import hex.genmodel.utils.DistributionFamily;
import hex.tree.*;
import hex.tree.DTree.DecidedNode;
import hex.tree.DTree.LeafNode;
import hex.tree.DTree.UndecidedNode;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import water.Iced;
import water.Job;
import water.Key;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.util.PrettyPrint;
import water.util.TwoDimTable;

import java.util.*;

import static water.util.RandomUtils.getRNG;
import static hex.tree.isofor.IsolationForestModel.IsolationForestParameters;
import static hex.tree.isofor.IsolationForestModel.IsolationForestOutput;

/**
 * Isolation Forest
 */
public class IsolationForest extends SharedTree<IsolationForestModel, IsolationForestParameters, IsolationForestOutput> {

    @Override
    public ModelCategory[] can_build() {
        return new ModelCategory[]{
                ModelCategory.AnomalyDetection
        };
    }

    @Override
    public BuilderVisibility builderVisibility() {
        return BuilderVisibility.Stable;
    }

    // Called from an http request
    public IsolationForest(IsolationForestParameters parms) {
        super(parms); System.out.println("IsolationForest");
        init(false);
    }

    public IsolationForest(IsolationForestParameters parms, Key<IsolationForestModel> key) {
        super(parms, key);
        init(false);
    }

    public IsolationForest(IsolationForestParameters parms, Job job) {
        super(parms, job);
        init(false);
    }

    public IsolationForest(boolean startup_once) {
        super(new IsolationForestParameters(), startup_once);
    }

    @Override
    protected Driver trainModelImpl() {
        System.out.println("trainModelImpl");
        return new IsolationForestDriver();
    }

    @Override
    public boolean scoreZeroTrees() {
        return false;
    }

    @Override
    public boolean isSupervised() {
        return false;
    }

    @Override
    protected ScoreKeeper.ProblemType getProblemType() {
        return ScoreKeeper.ProblemType.anomaly_detection;
    }

    private transient VarSplits _var_splits;

    @Override
    public void init(boolean expensive) {
        super.init(expensive);
        // Initialize local variables
        if (_parms._mtries < 1 && _parms._mtries != -1 && _parms._mtries != -2)
            error("_mtries", "mtries must be -1 (converted to sqrt(features)) or -2 (All features) or >= 1 but it is " + _parms._mtries);
        if (_train != null) {
            int ncols = _train.numCols();
            if (_parms._mtries != -1 && _parms._mtries != -2 && !(1 <= _parms._mtries && _parms._mtries <= ncols))
                error("_mtries", "Computed mtries should be -1 or -2 or in interval [1," + ncols + "] but it is " + _parms._mtries);
        }
        if (_parms._distribution != DistributionFamily.AUTO && _parms._distribution != DistributionFamily.gaussian) {
            throw new IllegalStateException("Isolation Forest doesn't expect the distribution to be specified by the user");
        }
        _parms._distribution = DistributionFamily.gaussian;
    }

    @Override
    protected void validateRowSampleRate() {
        System.out.println("validateRowSampleRate");
        if (_parms._sample_rate == -1) {
            if (_parms._sample_size <= 0) {
                error("_sample_size", "Sample size needs to be a positive integer number but it is " + _parms._sample_size);
            } else if (_train != null && _train.numRows() > 0) {
                _parms._sample_rate = _parms._sample_size / (double) _train.numRows();
            }
        }
    }

    // avalenta - tady si podle me vyrobim tolik samplu kolik chci udelat stromu
    // response = vysvetlovana promena
    private void randomResp(final long seed, final int iteration) {
        System.out.println("randomResp");
        new MRTask() {
            @Override
            public void map(Chunk chks[]) {
                Chunk c = chk_work(chks, 0);
                final long chunk_seed = seed + (c.start() * (1 + iteration));
                for (int i = 0; i < c._len; i++) {
                    double rnd = getRNG(chunk_seed + i).nextDouble();
                    chk_work(chks, 0).set(i, rnd);
                }
            }
        }.doAll(_train);
    }

    @Override
    protected DTree.DecidedNode makeDecided(DTree.UndecidedNode udn, DHistogram hs[], Constraints cs) {
        System.out.println("makeDecided");
        return new IFDecidedNode(udn, hs, cs);
    }

    private class IFDecidedNode extends DTree.DecidedNode {

        private IFDecidedNode(DTree.UndecidedNode n, DHistogram[] hs, Constraints cs) {
            super(n, hs, cs);
        }

        @Override
        public DTree.Split bestCol(DTree.UndecidedNode u, DHistogram hs[], Constraints cs) {
            System.out.println("bestCol = pick the best column from the given histograms");
            if (hs == null) return null;
            final int maxCols = u._scoreCols == null /* all cols */ ? hs.length : u._scoreCols.length;
            List<FindSplits> findSplits = new ArrayList<>();
            for (int i = 0; i < maxCols; i++) {
                int col = u._scoreCols == null ? i : u._scoreCols[i];
                if (hs[col] == null || hs[col].nbins() <= 1) continue;
                findSplits.add(new FindSplits(hs, cs, col, u));
            }
            Collections.shuffle(findSplits, _rand);
            for (FindSplits fs : findSplits) {
                DTree.Split s = fs.computeSplit();
                if (s != null) {
                    return s;
                }
            }
            return null;
        }
    }

    @Override
    protected void addCustomInfo(IsolationForestOutput out) {
        System.out.println("addCustomInfo");
        if (_var_splits != null) {
            out._var_splits = _var_splits;
            out._variable_splits = _var_splits.toTwoDimTable(out.features(), "Variable Splits");
        }
    }

    // ----------------------
    private class IsolationForestDriver extends Driver {
        @Override
        protected boolean doOOBScoring() { // avalenta - co je OOB? OUt of bound
            System.out.println("doOOBScoring");
            return true;
        }

        @Override
        protected void initializeModelSpecifics() {
            System.out.println("initializeModelSpecifics");
            _mtry_per_tree = Math.max(1, (int) (_parms._col_sample_rate_per_tree * _ncols));
            if (!(1 <= _mtry_per_tree && _mtry_per_tree <= _ncols))
                throw new IllegalArgumentException("Computed mtry_per_tree should be in interval <1," + _ncols + "> but it is " + _mtry_per_tree);
            if (_parms._mtries == -2) { //mtries set to -2 would use all columns in each split regardless of what column has been dropped during train
                _mtry = _ncols;
            } else if (_parms._mtries == -1) {
                _mtry = (isClassifier() ? Math.max((int) Math.sqrt(_ncols), 1) : Math.max(_ncols / 3, 1)); // classification: mtry=sqrt(_ncols), regression: mtry=_ncols/3
            } else {
                _mtry = _parms._mtries;
            }
            if (!(1 <= _mtry && _mtry <= _ncols)) {
                throw new IllegalArgumentException("Computed mtry should be in interval <1," + _ncols + "> but it is " + _mtry);
            }

            _initialPrediction = 0;
            _var_splits = new VarSplits(_ncols);
        }

        // --------------------------------------------------------------------------
        // Build the next random k-trees representing tid-th tree 
        // avalenta co to je ktree a tid-th three?
        @Override
        protected boolean buildNextKTrees() {
            System.out.println("buildNextKTrees");
            // avalenta - tady mi to pripada jako rozdeleni dat do chunku (subsamplig v podstate?) proc se to jmenuje randomResponse?
            // Create a Random response
            randomResp(_parms._seed, _model._output._ntrees);

            final long rseed = _rand.nextLong();
            final DTree tree = new DTree(_train, _ncols, _mtry, _mtry_per_tree, rseed, _parms);
            final DTree[] ktrees = {tree};

            // avalenta - mozna zde je subsampling
            // break point
            new Sample(tree, _parms._sample_rate, null)
                    .dfork(null, new Frame(vec_nids(_train, 0), vec_work(_train, 0)), _parms._build_tree_one_node)
                    .getResult();
            // break point - zmena v trainu
            
            // Assign rows to nodes - fill the "NIDs" column(s)
            // avalenta - tohle tvorba stromu? Metoda iTree s paperu?
            growTree(rseed, ktrees);

            // Reset NIDs
            CalculatePaths stats = new CalculatePaths(ktrees[0]).doAll(_train, _parms._build_tree_one_node);

            // Grow the model by K-trees
            _model._output.addKTrees(ktrees);
            _model._output._min_path_length = stats._minPathLength;
            _model._output._max_path_length = stats._maxPathLength;

            return false; // never stop early
        }

        // Assumes that the "Work" column are filled with copy of a random generated response
        private void growTree(long rseed, final DTree[] ktrees) {
            System.out.println("growTree");
            // Initial set of histograms.  All trees; one leaf per tree (the root
            // leaf); all columns
            // avalenta - tohle mi spocita min a max s kazdeho sloupce?
            DHistogram hcs[][][] = new DHistogram[_nclass][1/*just root leaf*/][_ncols];

            // Adjust real bins for the top-levels
            int adj_nbins = Math.max(_parms._nbins_top_level, _parms._nbins);

            // Initially setup as-if an empty-split had just happened
            final DTree tree = ktrees[0];
            
            // avalenta - tohle je podle me uplne zbytecnej radek
            new UndecidedNode(tree, -1, DHistogram.initialHist(_train, _ncols, adj_nbins, hcs[0][0], rseed, _parms, getGlobalQuantilesKeys(), null), null); // The "root" node

            // ----
            // One Big Loop till the ktrees are of proper depth.
            // Adds a layer to the trees each pass.
            final int[] leafs = new int[1];
            for (int depth = 0; depth < _parms._max_depth; depth++) {
                // avalenta - co to dela? Vrstva stromu?
                hcs = buildLayer(_train, _parms._nbins, _parms._nbins_cats, ktrees, leafs, hcs, _parms._build_tree_one_node);
                // If we did not make any new splits, then the tree is split-to-death
                // avalenta - tady by se mozna dalo nacpat do predikci vypocet anomaly score, pokud bych dostal nody na poslednim radku a pocet dat ktery nodum odpovidaj 
                if (hcs == null) break;
            }

            // Each tree bottomed-out in a DecidedNode; go 1 more level and insert
            // LeafNodes to hold predictions. //avalenta - jaky predikce?
            int leaf = tree.len();
            int depths[] = new int[leaf];
            for (int nid = 0; nid < leaf; nid++) {
                // z decided delam leaf
                if (tree.node(nid) instanceof DecidedNode) {
                    // avalenta - tady vidim splitovani, tohle se bude u EIF nahrazovat, prepsat metodu computeSplitImpl?..
                    DecidedNode dn = tree.decided(nid);
                    if (dn._split == null) { // No decision here, no row should have this NID now
                        if (nid == 0) {               // Handle the trivial non-splitting tree
                            LeafNode ln = new LeafNode(tree, -1, 0);
                            ln._pred = 0;
                        }
                        continue;
                    }
                    depths[nid] = dn._pid >= 0 ? depths[dn._pid] + 1 : 0;
                    for (int i = 0; i < dn._nids.length; i++) {
                        int cnid = dn._nids[i];
                        if (cnid == -1 || // Bottomed out (predictors or responses known constant)
                                tree.node(cnid) instanceof UndecidedNode || // Or chopped off for depth
                                (tree.node(cnid) instanceof DecidedNode &&  // Or not possible to split
                                        ((DecidedNode) tree.node(cnid))._split == null)) {
                            LeafNode ln = new LeafNode(tree, nid);
                            ln._pred = depths[nid];  // Set depth as the prediction into the leaf
                            dn._nids[i] = ln.nid(); // Mark a leaf here
                        }
                    }
                }
            }
            updatePerFeatureInfo(tree, depths);
        }

        // avalenta - co tohle dela?
        private void updatePerFeatureInfo(DTree tree, int[] depths) {
            System.out.println("updatePerFeatureInfo");
            for (int i = 0; i < tree._len; i++) {
                DTree.Node n = tree.node(i);
                if (!(n instanceof DecidedNode))
                    continue;
                DecidedNode dn = (DecidedNode) n;
                DTree.Split split = dn._split;
                if (split == null)
                    continue;
                _var_splits.update(split.col(), split, depths[n.nid()]);
            }
        }

        // Collect and write predictions into leafs. // avalenta - jaky predikce, je tim mysleno hloubky? z radku 287?
        // avalenta - tady to vypada na metodu PathLength s paperu
        private class CalculatePaths extends MRTask<CalculatePaths> {
            private final DTree _tree;
            // OUT
            private int _minPathLength = Integer.MAX_VALUE;
            private int _maxPathLength = 0;

            private CalculatePaths(DTree tree) {
                _tree = tree;
            }

            @Override
            public void map(Chunk[] chks) {
                System.out.println("map");
                final Chunk tree = chk_tree(chks, 0);
                final Chunk nids = chk_nids(chks, 0); // Node-ids  for this tree/class
                final Chunk oobt = chk_oobt(chks); // avalenta - co to je?
                for (int row = 0; row < nids._len; row++) {
                    final int rawNid = (int) chk_nids(chks, 0).at8(row);
                    final boolean wasOOBRow = ScoreBuildHistogram.isOOBRow(rawNid);
                    final int nid = wasOOBRow ? ScoreBuildHistogram.oob2Nid(rawNid) : rawNid;
                    final int depth = getNodeDepth(chks, row, nid);
                    if (wasOOBRow) {
                        double oobcnt = oobt.atd(row) + 1;
                        oobt.set(row, oobcnt);
                    }
                    final int total_len = PathTracker.encodeNewPathLength(tree, row, depth, wasOOBRow);
                    _maxPathLength = Math.max(total_len, _maxPathLength);
                    _minPathLength = Math.min(total_len, _minPathLength);
                    // reset NIds
                    nids.set(row, 0);
                }
            }

            @Override
            public void reduce(CalculatePaths mrt) {
                _minPathLength = Math.min(_minPathLength, mrt._minPathLength);
                _maxPathLength = Math.max(_maxPathLength, mrt._maxPathLength);
            }

            int getNodeDepth(Chunk[] chks, int row, int nid) {
                if (_tree.root() instanceof LeafNode) {
                    return 0;
                } else {
                    if (_tree.node(nid) instanceof UndecidedNode)  // If we bottomed out the tree
                        nid = _tree.node(nid).pid();                 // Then take parent's decision
                    DecidedNode dn = _tree.decided(nid);           // Must have a decision point
                    if (dn._split == null)                         // Unable to decide?
                        dn = _tree.decided(_tree.node(nid).pid());   // Then take parent's decision
                    int leafnid = dn.getChildNodeID(chks, row);    // Decide down to a leafnode
                    double depth = ((LeafNode) _tree.node(leafnid)).pred();
                    assert (int) depth == depth;
                    return (int) depth;
                }
            }
        }

        @Override
        protected IsolationForestModel makeModel(Key modelKey, IsolationForestParameters parms) {
            System.out.println("makeModel");
            return new IsolationForestModel(modelKey, parms, new IsolationForestOutput(IsolationForest.this));
        }

    }


    @Override
    protected double score1(Chunk chks[], double weight, double offset, double fs[/*2*/], int row) {
        // avalenta - tady se plni tabulky po zavolani predict/score mozna sem nacpat lepsi vypocet score
        System.out.println("score1");
        assert weight == 1;
        int len = PathTracker.decodeOOBPathLength(chk_tree(chks, 0), row);
        fs[1] = len / chk_oobt(chks).atd(row); // average tree path length
        fs[0] = _model.normalizePathLength(fs[1] * _model._output._ntrees); // score
        return fs[0];
    }

    protected TwoDimTable createScoringHistoryTable() {
        System.out.println("createScoringHistoryTable");
        List<String> colHeaders = new ArrayList<>();
        List<String> colTypes = new ArrayList<>();
        List<String> colFormat = new ArrayList<>();
        colHeaders.add("Timestamp");
        colTypes.add("string");
        colFormat.add("%s");
        colHeaders.add("Duration");
        colTypes.add("string");
        colFormat.add("%s");
        colHeaders.add("Number of Trees");
        colTypes.add("long");
        colFormat.add("%d");
        colHeaders.add("Mean Tree Path Length");
        colTypes.add("double");
        colFormat.add("%.5f");
        colHeaders.add("Mean Anomaly Score");
        colTypes.add("double");
        colFormat.add("%.5f");
        if (_parms._custom_metric_func != null) {
            colHeaders.add("Training Custom");
            colTypes.add("double");
            colFormat.add("%.5f");
        }

        ScoreKeeper[] sks = _model._output._scored_train;

        int rows = 0;
        for (int i = 0; i < sks.length; i++) {
            if (i != 0 && Double.isNaN(sks[i]._anomaly_score)) continue;
            rows++;
        }
        TwoDimTable table = new TwoDimTable(
                "Scoring History", null,
                new String[rows],
                colHeaders.toArray(new String[0]),
                colTypes.toArray(new String[0]),
                colFormat.toArray(new String[0]),
                "");
        int row = 0;
        for (int i = 0; i < sks.length; i++) {
            if (i != 0 && Double.isNaN(sks[i]._anomaly_score)) continue;
            int col = 0;
            DateTimeFormatter fmt = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss");
            table.set(row, col++, fmt.print(_model._output._training_time_ms[i]));
            table.set(row, col++, PrettyPrint.msecs(_model._output._training_time_ms[i] - _job.start_time(), true));
            table.set(row, col++, i);
            ScoreKeeper st = sks[i];
            table.set(row, col++, st._anomaly_score);
            table.set(row, col++, st._anomaly_score_normalized);
            if (_parms._custom_metric_func != null) {
                table.set(row, col++, st._custom_metric);
            }
            assert col == colHeaders.size();
            row++;
        }
        return table;
    }

    @Override
    public boolean havePojo() {
        return false;
    }

    @Override
    public boolean haveMojo() {
        return true;
    }

    public static class VarSplits extends Iced<VarSplits> {
        public final int[] _splitCounts;
        public final float[] _aggSplitRatios;
        public final long[] _splitDepths;

        private VarSplits(int ncols) {
            System.out.println("VarSplits");
            _splitCounts = new int[ncols];
            _aggSplitRatios = new float[ncols];
            _splitDepths = new long[ncols];
        }

        void update(int col, DTree.Split split, int depth) {
            System.out.println("update");
            _aggSplitRatios[col] += Math.abs(split.n0() - split.n1()) / (split.n0() + split.n1());
            _splitCounts[col]++;
            _splitDepths[col] += depth + 1;
        }

        public TwoDimTable toTwoDimTable(String[] coef_names, String table_header) {

            double[][] dblCellValues = new double[_splitCounts.length][];
            for (int i = 0; i < _splitCounts.length; i++) {
                dblCellValues[i] = new double[]{_splitCounts[i], _aggSplitRatios[i], _splitDepths[i]};
            }

            String[] col_headers = {"Count", "Aggregated Split Ratios", "Aggregated Split Depths"};
            String[] col_types = {"int", "double", "long"};
            String[] col_formats = {"%10d", "%5f", "%10d"};

            return new TwoDimTable(table_header, null, coef_names, col_headers, col_types, col_formats,
                    "Variable", new String[_splitCounts.length][], dblCellValues);
        }
    }
}
