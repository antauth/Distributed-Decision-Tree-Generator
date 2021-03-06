package org.apache.mahout.decision;

import org.apache.commons.cli2.CommandLine;
import org.apache.commons.cli2.Group;
import org.apache.commons.cli2.Option;
import org.apache.commons.cli2.OptionException;
import org.apache.commons.cli2.builder.ArgumentBuilder;
import org.apache.commons.cli2.builder.DefaultOptionBuilder;
import org.apache.commons.cli2.builder.GroupBuilder;
import org.apache.commons.cli2.commandline.Parser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.CommandLineUtil;
import org.apache.mahout.decision.distributed.NodeExpander;
import org.apache.mahout.df.DFUtils;
import org.apache.mahout.df.data.Dataset;
import org.apache.mahout.df.node.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

public class BuildDecisionTree extends Configured implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BuildDecisionTree.class);

    private Path dataPath;

    private Path datasetPath;

    private Path outputPath;

    private Integer height;

    private Integer threshold;

    private Queue<Node> mrQueue = new LinkedList<Node>();

    public static void main(String[] args) throws Exception {
        ToolRunner.run(new Configuration(), new BuildDecisionTree(), args);
    }

    @Override
    public int run(String[] args) throws Exception {
        DefaultOptionBuilder obuilder = new DefaultOptionBuilder();
        ArgumentBuilder abuilder = new ArgumentBuilder();
        GroupBuilder gbuilder = new GroupBuilder();

        Option dataOpt = obuilder.withLongName("data").withShortName("d").withRequired(true).withArgument(
                abuilder.withName("path").withMinimum(1).withMaximum(1).create()).withDescription("Data path").create();

        Option datasetOpt = obuilder.withLongName("dataset").withShortName("ds").withRequired(true).withArgument(
                abuilder.withName("dataset").withMinimum(1).withMaximum(1).create()).withDescription("Dataset path")
                .create();

        Option outputOpt = obuilder.withLongName("output").withShortName("o").withRequired(true).withArgument(
                abuilder.withName("path").withMinimum(1).withMaximum(1).create()).
                withDescription("Output path, will contain the Decision Forest").create();

        Option heightOpt = obuilder.withLongName("height").withShortName("ht").withRequired(false).withArgument(
                abuilder.withName("height").withMinimum(1).withMaximum(1).create()).
                withDescription("The maximum height of the tree <optional>").create();

        Option thresholdOpt = obuilder.withLongName("in memory threshold").withShortName("imt").withRequired(true).withArgument(
                abuilder.withName("threshold").withMinimum(1).withMaximum(1).create()).
                withDescription("The minimum number of records to have in a data subset before it is run in memory").create();

        Option helpOpt = obuilder.withLongName("help").withDescription("Print out help").withShortName("h")
                .create();

        Group group = gbuilder.withName("Options").withOption(dataOpt).withOption(datasetOpt)
                .withOption(outputOpt).withOption(heightOpt).withOption(helpOpt).create();

        try {
            Parser parser = new Parser();
            parser.setGroup(group);
            CommandLine cmdLine = parser.parse(args);

            if (cmdLine.hasOption("help")) {
                CommandLineUtil.printHelp(group);
                return -1;
            }

            String dataName = cmdLine.getValue(dataOpt).toString();
            String datasetName = cmdLine.getValue(datasetOpt).toString();
            String outputName = cmdLine.getValue(outputOpt).toString();
            String heightName = cmdLine.getValue(heightOpt).toString();
            String thresholdName = cmdLine.getValue(thresholdOpt).toString();

            log.debug("data : {}", dataName);
            log.debug("dataset : {}", datasetName);
            log.debug("output : {}", outputName);
            log.debug("height : {}", heightName);
            log.debug("threshold : {}", thresholdName);

            dataPath = new Path(dataName);
            datasetPath = new Path(datasetName);
            outputPath = new Path(outputName);

        } catch (OptionException e) {
            log.error("Exception", e);
            CommandLineUtil.printHelp(group);
            return -1;
        }

        buildTree();

        return 0;
    }

    private void buildTree() throws IOException, ClassNotFoundException, InterruptedException {
        // make sure the output path does not exist
        FileSystem ofs = outputPath.getFileSystem(getConf());
        if (ofs.exists(outputPath)) {
            log.error("Output path already exists");
            return;
        }

        Dataset dataset = Dataset.load(getConf(), datasetPath);

        log.info("Building the tree...");
        long time = System.currentTimeMillis();

        Configuration conf = new Configuration();
        conf.set("treePath", outputPath.toString());

        Path modelPath = new Path(outputPath.getName(), "tree");

        /*
            The initial (root) node must be expanded. Assuming that the root node will not be an in memory operation
         */
        Node root = new UnknownNode(false);
        DFUtils.storeWritable(conf, modelPath, root);

        

        boolean mrJobsRemaining = true;
        int phaseCount = 0;

        while (mrJobsRemaining) {
            Job job = new Job(conf, "PLANET - Node Expansion Phase #" + phaseCount);
            job.setMapperClass(NodeExpander.Map.class);
            job.setReducerClass(NodeExpander.Reduce.class);

            job.setOutputKeyClass(Split.class);
            job.setOutputValueClass(DoubleWritable.class);

            FileInputFormat.addInputPath(job, datasetPath);
            FileOutputFormat.setOutputPath(job, outputPath);

            job.waitForCompletion(true);
            
            phaseCount++;
        }


        time = System.currentTimeMillis() - time;
        log.info("Build Time: {}", DFUtils.elapsedTime(time));

        // store the decision tree in the output path
        Path treePath = new Path(outputPath, "tree.seq");
        log.info("Storing the tree in: " + treePath);
        DFUtils.storeWritable(getConf(), treePath, root);
    }
}