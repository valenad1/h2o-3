package hex.genmodel.tools;

import hex.genmodel.GenModel;
import hex.genmodel.MojoModel;
import hex.genmodel.algos.tree.SharedTreeGraph;
import hex.genmodel.algos.tree.SharedTreeGraphConverter;

import org.graphstream.graph.ElementNotFoundException;
import org.graphstream.graph.Graph;
import org.graphstream.graph.implementations.MultiGraph;
import org.graphstream.stream.GraphParseException;
import org.graphstream.stream.file.FileSinkImages;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


/**
 * Print dot (graphviz) representation of one or more trees in a DRF or GBM model.
 */
public class PrintMojo {
  private GenModel genModel;
  private static boolean printRaw = false;
  private static int treeToPrint = -1;
  private static int maxLevelsToPrintPerEdge = 10;
  private static boolean detail = false;
  private static String outputFileName = null;
  private static String optionalTitle = null;
  private static PrintTreeOptions pTreeOptions;
  private static boolean internal;
  private String outputPngName = null;
  private static final String tmpOutputFileName = "tmpOutputFileName.dot";
  private static final String stylesheet = "" +
          "node {" +
          "   text-background-mode: rounded-box;" +
          "   text-background-color: gray; " +
          "   text-color: #222;" +
          "}" +
          "node.plus {" +
          "   text-background-color: #F33; " +
          "   text-color: #DDD;" +
          "}";

  public static void main(String[] args) {
    // Parse command line arguments
    PrintMojo main = new PrintMojo();
    main.parseArgs(args);

    // Run the main program
    try {
      main.run();
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(2);
    }

    // Success
    System.exit(0);
  }

  private void loadMojo(String modelName) throws IOException {
    genModel = MojoModel.load(modelName);
  }

  private static void usage() {
    System.out.println("Emit a human-consumable graph of a model for use with dot (graphviz).");
    System.out.println("The currently supported model types are DRF, GBM and XGBoost.");
    System.out.println("");
    System.out.println("Usage:  java [...java args...] hex.genmodel.tools.PrintMojo [--tree n] [--levels n] [--title sss] [-o outputFileName] [--direct imageOutputFileName]");
    System.out.println("");
    System.out.println("    --tree          Tree number to print.");
    System.out.println("                    [default all]");
    System.out.println("");
    System.out.println("    --levels        Number of levels per edge to print.");
    System.out.println("                    [default " + maxLevelsToPrintPerEdge + "]");
    System.out.println("");
    System.out.println("    --title         (Optional) Force title of tree graph.");
    System.out.println("");
    System.out.println("    --detail        Specify to print additional detailed information like node numbers.");
    System.out.println("");
    System.out.println("    --input | -i    Input mojo file.");
    System.out.println("");
    System.out.println("    --output | -o   Output dot filename.");
    System.out.println("                    [default stdout]");
    System.out.println("    --decimalplaces | -d    Set decimal places of all numerical values.");
    System.out.println("");
    System.out.println("    --fontsize | -f    Set font sizes of strings.");
    System.out.println("");
    System.out.println("    --internal    Internal H2O representation of the decision tree (splits etc.) is used for generating the GRAPHVIZ format.");
    System.out.println("");
    System.out.println("    --direct    Produce directly an image of the Tree instead of the dot-format.");
    System.out.println("");
    System.out.println("");
    System.out.println("Example:");
    System.out.println("");
    System.out.println("    (brew install graphviz)");
    System.out.println("    java -cp h2o.jar hex.genmodel.tools.PrintMojo --tree 0 -i model_mojo.zip -o model.gv -f 20 -d 3");
    System.out.println("    dot -Tpng model.gv -o model.png");
    System.out.println("    open model.png");
    System.out.println();
    System.exit(1);
  }

  private void parseArgs(String[] args) {
    int nPlaces = -1;
    int fontSize = 14; // default size is 14
    boolean setDecimalPlaces = false;
    try {
      for (int i = 0; i < args.length; i++) {
        String s = args[i];
        switch (s) {
          case "--tree":
            i++;
            if (i >= args.length) usage();
            s = args[i];
            try {
              treeToPrint = Integer.parseInt(s);
            } catch (Exception e) {
              System.out.println("ERROR: invalid --tree argument (" + s + ")");
              System.exit(1);
            }
            break;

          case "--levels":
            i++;
            if (i >= args.length) usage();
            s = args[i];
            try {
              maxLevelsToPrintPerEdge = Integer.parseInt(s);
            } catch (Exception e) {
              System.out.println("ERROR: invalid --levels argument (" + s + ")");
              System.exit(1);
            }
            break;

          case "--title":
            i++;
            if (i >= args.length) usage();
            optionalTitle = args[i];
            break;

          case "--detail":
            detail = true;
            break;

          case "--input":
          case "-i":
            i++;
            if (i >= args.length) usage();
            s = args[i];
            loadMojo(s);
            break;

          case "--fontsize":
          case "-f":
            i++;
            if (i >= args.length) usage();
            s = args[i];
            fontSize = Integer.parseInt(s);
            break;

          case "--decimalplaces":
          case "-d":
            i++;
            if (i >= args.length) usage();
            setDecimalPlaces=true;
            s = args[i];
            nPlaces = Integer.parseInt(s);
            break;

          case "--direct":
            i++;
            if (i >= args.length) usage();
            outputPngName = args[i];
            break;
            
          case "--raw":
            printRaw = true;
            break;
          case "--internal":
            internal = true;
            break;

          case "-o":
          case "--output":
            i++;
            if (i >= args.length) usage();
            outputFileName = args[i];
            break;

          default:
            System.out.println("ERROR: Unknown command line argument: " + s);
            usage();
            break;
        }
      }
      pTreeOptions = new PrintTreeOptions(setDecimalPlaces, nPlaces, fontSize, internal);
    } catch (Exception e) {
      e.printStackTrace();
      usage();
    }
  }

  private void validateArgs() {
    if (genModel == null) {
      System.out.println("ERROR: Must specify -i");
      usage();
    }
  }

  private void run() throws Exception {
    validateArgs();
    if (outputFileName != null) {
      handlePrintingToOutputFile();
    } else if (outputPngName != null) {
      handlePrintingToTmpFile();
    } else {
      handlePrintingToConsole();
    }
  }
  
  private void handlePrintingToOutputFile() throws IOException, GraphParseException, ElementNotFoundException {
    Path dotSourceFilePath = Paths.get(outputFileName);
    try (PrintStream os = new PrintStream(new FileOutputStream(dotSourceFilePath.toFile()))) {
      generateDotFromSource(os, dotSourceFilePath);
    }
  }

  private void handlePrintingToTmpFile() throws IOException, GraphParseException, ElementNotFoundException {
    Path dotSourceFilePath = Files.createTempFile("", tmpOutputFileName);
    try (PrintStream os = new PrintStream(new FileOutputStream(dotSourceFilePath.toFile()))) {
      generateDotFromSource(os, dotSourceFilePath);
    }
  }

  private void handlePrintingToConsole() throws IOException, GraphParseException, ElementNotFoundException {
    generateDotFromSource(System.out, null);
  }

  private void generateDotFromSource(PrintStream os, Path dotSourceFilePath) throws IOException, GraphParseException, ElementNotFoundException {
    if (!(genModel instanceof SharedTreeGraphConverter)) {
      System.out.println("ERROR: Unknown MOJO type");
      System.exit(1);
    }
    SharedTreeGraphConverter treeBackedModel = (SharedTreeGraphConverter) genModel;
    final SharedTreeGraph g = treeBackedModel.convert(treeToPrint, null);
    if (printRaw) {
      g.print();
    }
    g.printDot(os, maxLevelsToPrintPerEdge, detail, optionalTitle, pTreeOptions, true);
    if (outputPngName != null) {
        Graph graph = new MultiGraph(outputPngName) ;
        graph.read(dotSourceFilePath.toString());
        saveGraph(graph);
    }
  }

  public void saveGraph(Graph graph) throws IOException {
    final FileSinkImages fsi = new FileSinkImages();
    fsi.setLayoutPolicy(FileSinkImages.LayoutPolicy.COMPUTED_FULLY_AT_NEW_IMAGE);
    fsi.setQuality(FileSinkImages.Quality.HIGH);
    fsi.setResolution(FileSinkImages.Resolutions.HD1080);
    fsi.setRenderer(FileSinkImages.RendererType.SCALA);
    fsi.setStyleSheet(stylesheet);
    fsi.writeAll(graph, outputPngName);
  }

  public static class PrintTreeOptions {
    public boolean _setDecimalPlace = false;
    public int _nPlaces = -1;
    public int _fontSize = 14;  // default
    public boolean _internal;

    public PrintTreeOptions(boolean setdecimalplaces, int nplaces, int fontsize, boolean internal) {
      _setDecimalPlace = setdecimalplaces;
      _nPlaces = _setDecimalPlace?nplaces:_nPlaces;
      _fontSize = fontsize;
      _internal = internal;
    }

    public float roundNPlace(float value) {
      if (_nPlaces < 0)
        return value;
      double sc = Math.pow(10, _nPlaces);
      return (float) (Math.round(value*sc)/sc);
    }
  }
}
