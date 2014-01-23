/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.tez;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pig.FuncSpec;
import org.apache.pig.IndexableLoadFunc;
import org.apache.pig.LoadFunc;
import org.apache.pig.OrderedLoadFunc;
import org.apache.pig.PigConfiguration;
import org.apache.pig.PigException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.MRCompilerException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.MergeJoinIndexer;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.ScalarPhyFinder;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.UDFFinder;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.ConstantExpression;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POProject;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POUserFunc;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhyPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.LitePackager;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POCollectedGroup;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POCounter;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POCross;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PODistinct;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POFRJoin;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POFilter;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POForEach;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POGlobalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLimit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLoad;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLocalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POMergeCogroup;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POMergeJoin;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PONative;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPoissonSample;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PORank;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POReservoirSample;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POSkewedJoin;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POSort;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POSplit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POStore;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POStream;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POUnion;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.Packager.PackageType;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.util.PlanHelper;
import org.apache.pig.backend.hadoop.executionengine.tez.POLocalRearrangeTezFactory.LocalRearrangeType;
import org.apache.pig.data.DataType;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.builtin.DefaultIndexableLoader;
import org.apache.pig.impl.builtin.FindQuantiles;
import org.apache.pig.impl.builtin.GetMemNumRows;
import org.apache.pig.impl.builtin.PartitionSkewedKeys;
import org.apache.pig.impl.io.FileLocalizer;
import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.plan.DepthFirstWalker;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.Operator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.OperatorPlan;
import org.apache.pig.impl.plan.PlanException;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.impl.util.CompilerUtils;
import org.apache.pig.impl.util.MultiMap;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.Pair;
import org.apache.pig.impl.util.Utils;
import org.apache.pig.newplan.logical.relational.LOJoin;
import org.apache.tez.dag.api.EdgeProperty.DataMovementType;
import org.apache.tez.runtime.library.input.ShuffledUnorderedKVInput;
import org.apache.tez.runtime.library.output.OnFileUnorderedKVOutput;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * The compiler that compiles a given physical plan into a DAG of Tez
 * operators which can then be converted into the JobControl structure.
 */
public class TezCompiler extends PhyPlanVisitor {
    private static final Log LOG = LogFactory.getLog(TezCompiler.class);

    private PigContext pigContext;
    private Properties pigProperties;

    //The plan that is being compiled
    private PhysicalPlan plan;

    //The plan of Tez Operators
    private TezOperPlan tezPlan;

    //The current Tez Operator that is being compiled
    private TezOperator curTezOp;

    //The output of compiling the inputs
    private TezOperator[] compiledInputs = null;

    //The split operators seen till now.
    //During the traversal a split is the only operator that can be revisited
    //from a different path.
    private Map<OperatorKey, TezOperator> splitsSeen;

    private NodeIdGenerator nig;

    private String scope;

    private UDFFinder udfFinder;

    private Map<PhysicalOperator, TezOperator> phyToTezOpMap;

    public static final String USER_COMPARATOR_MARKER = "user.comparator.func:";
    public static final String FILE_CONCATENATION_THRESHOLD = "pig.files.concatenation.threshold";
    public static final String OPTIMISTIC_FILE_CONCATENATION = "pig.optimistic.files.concatenation";

    private int fileConcatenationThreshold = 100;
    private boolean optimisticFileConcatenation = false;

    private POLocalRearrangeTezFactory localRearrangeFactory;

    public TezCompiler(PhysicalPlan plan, PigContext pigContext)
            throws TezCompilerException {
        super(plan, new DepthFirstWalker<PhysicalOperator, PhysicalPlan>(plan));
        this.plan = plan;
        this.pigContext = pigContext;
        pigProperties = pigContext.getProperties();
        splitsSeen = Maps.newHashMap();
        tezPlan = new TezOperPlan();
        nig = NodeIdGenerator.getGenerator();
        udfFinder = new UDFFinder();
        List<PhysicalOperator> roots = plan.getRoots();
        if((roots == null) || (roots.size() <= 0)) {
            int errCode = 2053;
            String msg = "Internal error. Did not find roots in the physical plan.";
            throw new TezCompilerException(msg, errCode, PigException.BUG);
        }
        scope = roots.get(0).getOperatorKey().getScope();
        localRearrangeFactory = new POLocalRearrangeTezFactory(scope, nig);
        phyToTezOpMap = Maps.newHashMap();

        fileConcatenationThreshold = Integer.parseInt(pigProperties
                .getProperty(FILE_CONCATENATION_THRESHOLD, "100"));
        optimisticFileConcatenation = pigProperties.getProperty(
                OPTIMISTIC_FILE_CONCATENATION, "false").equals("true");
        LOG.info("File concatenation threshold: " + fileConcatenationThreshold
                + " optimistic? " + optimisticFileConcatenation);
    }

    public TezOperPlan getTezPlan() {
        return tezPlan;
    }

    // Segment a single DAG into a DAG graph
    public TezPlanContainer getPlanContainer() throws PlanException {
        TezPlanContainer tezPlanContainer = new TezPlanContainer(pigContext);
        TezPlanContainerNode node = new TezPlanContainerNode(new OperatorKey(scope, nig.getNextNodeId(scope)), tezPlan);
        tezPlanContainer.add(node);
        tezPlanContainer.split(node);
        return tezPlanContainer;
    }

    /**
     * The front-end method that the user calls to compile the plan. Assumes
     * that all submitted plans have a Store operators as the leaf.
     * @return A Tez plan
     * @throws IOException
     * @throws PlanException
     * @throws VisitorException
     */
    public TezOperPlan compile() throws IOException, PlanException, VisitorException {
        List<PhysicalOperator> leaves = plan.getLeaves();

        if (!pigContext.inIllustrator) {
            for (PhysicalOperator op : leaves) {
                if (!(op instanceof POStore)) {
                    int errCode = 2025;
                    String msg = "Expected leaf of reduce plan to " +
                        "always be POStore. Found " + op.getClass().getSimpleName();
                    throw new TezCompilerException(msg, errCode, PigException.BUG);
                }
            }
        }

        // get all stores, sort them in order(operator id) and compile their
        // plans
        List<POStore> stores = PlanHelper.getPhysicalOperators(plan, POStore.class);
        List<PhysicalOperator> ops = Lists.newArrayList();
        ops.addAll(stores);
        Collections.sort(ops);

        for (PhysicalOperator op : ops) {
            compile(op);
            if (curTezOp.isSplitSubPlan()) {
                // Set inputs to null as POSplit will attach input to roots
                for (PhysicalOperator root : curTezOp.plan.getRoots()) {
                    root.setInputs(null);
                }
                TezOperator splitOp = splitsSeen.get(curTezOp.getSplitOperatorKey());
                POSplit split = findPOSplit(splitOp, curTezOp.getSplitOperatorKey());
                split.addPlan(curTezOp.plan);
                addSubPlanPropertiesToParent(splitOp, curTezOp);
                curTezOp = splitOp;
            }
        }

        for (TezOperator tezOper : splitsSeen.values()) {
            int idx = 0;
            List<POStore> strs = PlanHelper.getPhysicalOperators(tezOper.plan,
                    POStore.class);
            for (POStore op : strs) {
                op.setIndex(idx++);
            }
            tezOper.setClosed(true);
        }

        connectSoftLink();

        return tezPlan;
    }

    private void addSubPlanPropertiesToParent(TezOperator parentOper, TezOperator subPlanOper) {
        if (subPlanOper.requestedParallelism > parentOper.requestedParallelism) {
            parentOper.requestedParallelism = subPlanOper.requestedParallelism;
        }
        if (subPlanOper.UDFs != null) {
            parentOper.UDFs.addAll(subPlanOper.UDFs);
        }
        if (subPlanOper.outEdges != null) {
            for (Entry<OperatorKey, TezEdgeDescriptor> entry: subPlanOper.outEdges.entrySet()) {
                parentOper.outEdges.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void connectSoftLink() throws PlanException, IOException {
        for (PhysicalOperator op : plan) {
            if (plan.getSoftLinkPredecessors(op)!=null) {
                for (PhysicalOperator pred : plan.getSoftLinkPredecessors(op)) {
                    TezOperator from = phyToTezOpMap.get(pred);
                    TezOperator to = phyToTezOpMap.get(op);
                    if (from==to) {
                        continue;
                    }
                    if (tezPlan.getPredecessors(to)==null || !tezPlan.getPredecessors(to).contains(from)) {
                        tezPlan.connect(from, to);
                    }
                }
            }
        }
    }

    /**
     * Compiles the plan below op into a Tez Operator
     * and stores it in curTezOp.
     * @param op
     * @throws IOException
     * @throws PlanException
     * @throws VisitorException
     */
    private void compile(PhysicalOperator op) throws IOException, PlanException, VisitorException {
        // An artifact of the Visitor. Need to save this so that it is not
        // overwritten.
        TezOperator[] prevCompInp = compiledInputs;

        // Compile each predecessor into the TezOperator and  store them away so
        // that we can use them for compiling op.
        List<PhysicalOperator> predecessors = plan.getPredecessors(op);
        if (predecessors != null && predecessors.size() > 0) {
            // When processing an entire script (multiquery), we can get into a
            // situation where a load has predecessors. This means that it
            // depends on some store earlier in the plan. We need to take that
            // dependency and connect the respective Tez operators, while at the
            // same time removing the connection between the Physical operators.
            // That way the jobs will run in the right order.
            if (op instanceof POLoad) {
                if (predecessors.size() != 1) {
                    int errCode = 2125;
                    String msg = "Expected at most one predecessor of load. Got "+predecessors.size();
                    throw new PlanException(msg, errCode, PigException.BUG);
                }

                PhysicalOperator p = predecessors.get(0);
                TezOperator oper = null;
                if (p instanceof POStore) {
                    oper = phyToTezOpMap.get(p);
                } else {
                    int errCode = 2126;
                    String msg = "Predecessor of load should be a store. Got "+p.getClass();
                    throw new PlanException(msg, errCode, PigException.BUG);
                }

                // Need new operator
                curTezOp = getTezOp();
                curTezOp.plan.add(op);
                tezPlan.add(curTezOp);

                plan.disconnect(op, p);
                oper.segmentBelow = true;
                tezPlan.connect(oper, curTezOp);
                phyToTezOpMap.put(op, curTezOp);
                return;
            }

            Collections.sort(predecessors);
            compiledInputs = new TezOperator[predecessors.size()];
            int i = -1;
            for (PhysicalOperator pred : predecessors) {
                compile(pred);
                compiledInputs[++i] = curTezOp;
            }
        } else {
            // No predecessors. Mostly a load. But this is where we start. We
            // create a new TezOp and add its first operator op. Also this
            // should be added to the tezPlan.
            curTezOp = getTezOp();
            curTezOp.plan.add(op);
            if (op !=null && op instanceof POLoad) {
                if (((POLoad)op).getLFile()!=null && ((POLoad)op).getLFile().getFuncSpec()!=null)
                    curTezOp.UDFs.add(((POLoad)op).getLFile().getFuncSpec().toString());
            }
            tezPlan.add(curTezOp);
            phyToTezOpMap.put(op, curTezOp);
            return;
        }

        // Now we have the inputs compiled. Do something with the input oper op.
        op.visit(this);
        if (op.getRequestedParallelism() > curTezOp.requestedParallelism) {
            curTezOp.requestedParallelism = op.getRequestedParallelism();
        }
        compiledInputs = prevCompInp;
    }

    /**
     * Start a new TezOperator whose plan will be the sub-plan of POSplit
     *
     * @param splitOperatorKey
     *            OperatorKey of the POSplit for which the new plan is a sub-plan
     * @return the new TezOperator
     * @throws PlanException
     */
    private TezOperator startNew(OperatorKey splitOperatorKey) throws PlanException {
        TezOperator ret = getTezOp();
        ret.setSplitOperatorKey(splitOperatorKey);
        return ret;
    }

    private void nonBlocking(PhysicalOperator op) throws PlanException, IOException {
        TezOperator tezOp;
        if (compiledInputs.length == 1) {
            tezOp = compiledInputs[0];
            if (tezOp.isClosed()) {
                int errCode = 2027;
                String msg = "Tez operator has been closed. This is unexpected for a merge.";
                throw new PlanException(msg, errCode, PigException.BUG);
            }
        } else {
            tezOp = merge(compiledInputs);
        }
        tezOp.plan.addAsLeaf(op);
        curTezOp = tezOp;
    }

    private void blocking() throws IOException, PlanException {
        TezOperator newTezOp = getTezOp();
        tezPlan.add(newTezOp);
        for (TezOperator tezOp : compiledInputs) {
            tezOp.setClosed(true);
            handleSplitAndConnect(tezPlan, tezOp, newTezOp);
        }
        curTezOp = newTezOp;
    }

    private TezEdgeDescriptor handleSplitAndConnect(TezOperPlan tezPlan, TezOperator from, TezOperator to)
            throws PlanException {
        return handleSplitAndConnect(tezPlan, from, to, true);
    }

    private TezEdgeDescriptor handleSplitAndConnect(TezOperPlan tezPlan,
            TezOperator from, TezOperator to, boolean addToSplitPlan)
            throws PlanException {
        // Add edge descriptors from POLocalRearrange in POSplit
        // sub-plan to new operators
        PhysicalOperator leaf = from.plan.getLeaves().get(0);
        // It could be POStoreTez incase of sampling job in order by
        if (leaf instanceof POLocalRearrangeTez) {
            POLocalRearrangeTez lr = (POLocalRearrangeTez) leaf;
            lr.setOutputKey(to.getOperatorKey().toString());
        }
        TezEdgeDescriptor edge = null;
        if (from.isSplitSubPlan()) {
            TezOperator splitOp = splitsSeen.get(from.getSplitOperatorKey());
            if (addToSplitPlan) {
                // Set inputs to null as POSplit will attach input to roots
                for (PhysicalOperator root : from.plan.getRoots()) {
                    root.setInputs(null);
                }
                POSplit split = findPOSplit(splitOp, from.getSplitOperatorKey());
                split.addPlan(from.plan);
                addSubPlanPropertiesToParent(splitOp, curTezOp);
            }
            edge = TezCompilerUtil.connect(tezPlan, splitOp, to);
        } else {
            edge = TezCompilerUtil.connect(tezPlan, from, to);
        }
        return edge;
    }

    private POSplit findPOSplit(TezOperator tezOp, OperatorKey splitKey)
            throws PlanException {
        POSplit split = (POSplit) tezOp.plan.getOperator(splitKey);
        if (split != null) {
            // The split is the leaf operator.
            return split;
        } else {
            // The split is a nested split
            Stack<POSplit> stack = new Stack<POSplit>();
            split = (POSplit) tezOp.plan.getLeaves().get(0);
            stack.push(split);
            while (!stack.isEmpty()) {
                split = stack.pop();
                for (PhysicalPlan plan : split.getPlans()) {
                    PhysicalOperator op = plan.getLeaves().get(0);
                    if (op instanceof POSplit) {
                        split = (POSplit) op;
                        if (split.getOperatorKey().equals(splitKey)) {
                            return split;
                        } else {
                            stack.push(split);
                        }
                    }
                }
            }
        }
        // TODO: what should be the new error code??
        throw new PlanException(
                "Could not find the split operator " + splitKey, 2059,
                PigException.BUG);
    }

    /**
     * Remove the operator and the whole tree connected to that operator from
     * the plan. Only remove corresponding connected sub-plan if you encounter
     * another Split operator in the predecessor.
     *
     * @param op Operator to remove
     * @throws VisitorException
     */
    public void removeDupOpTreeOfSplit(TezOperPlan plan, TezOperator op)
            throws VisitorException {
        Stack<TezOperator> stack = new Stack<TezOperator>();
        stack.push(op);
        while (!stack.isEmpty()) {
            op = stack.pop();
            List<TezOperator> predecessors = plan.getPredecessors(op);
            if (predecessors != null) {
                for (TezOperator pred : predecessors) {
                    List<POSplit> splits = PlanHelper.getPhysicalOperators(
                            pred.plan, POSplit.class);
                    if (splits.isEmpty()) {
                        stack.push(pred);
                    } else {
                        for (POSplit split : splits) {
                            PhysicalPlan planToRemove = null;
                            for (PhysicalPlan splitPlan : split.getPlans()) {
                                PhysicalOperator phyOp = splitPlan.getLeaves().get(0);
                                if (phyOp instanceof POLocalRearrangeTez) {
                                    POLocalRearrangeTez lr = (POLocalRearrangeTez) phyOp;
                                    if (lr.getOutputKey().equals(
                                            op.getOperatorKey().toString())) {
                                        planToRemove = splitPlan;
                                        break;
                                    }
                                }
                            }
                            if (planToRemove != null) {
                                split.getPlans().remove(planToRemove);
                                break;
                            }
                        }
                    }
                }
            }
            plan.remove(op);
        }
    }

    /**
     * Merges the TezOperators in the compiledInputs into a single merged
     * TezOperator.
     *
     * Care is taken to remove the TezOperators that are merged from the TezPlan
     * and their connections moved over to the merged map TezOperator.
     *
     * Merge is implemented as a sequence of binary merges.
     * merge(PhyPlan finPlan, List<PhyPlan> lst) := finPlan, merge(p) foreach p in lst
     *
     * @param compiledInputs
     * @return merged TezOperator
     * @throws PlanException
     * @throws IOException
     */
    private TezOperator merge(TezOperator[] compiledInputs) throws PlanException {
        TezOperator ret = getTezOp();
        tezPlan.add(ret);

        Set<TezOperator> toBeConnected = Sets.newHashSet();
        List<TezOperator> toBeRemoved = Lists.newArrayList();
        List<PhysicalPlan> toBeMerged = Lists.newArrayList();

        for (TezOperator tezOp : compiledInputs) {
            if (!tezOp.isClosed()) {
                toBeRemoved.add(tezOp);
                toBeMerged.add(tezOp.plan);
                List<TezOperator> predecessors = tezPlan.getPredecessors(tezOp);
                if (predecessors != null) {
                    for (TezOperator predecessorTezOp : predecessors) {
                        toBeConnected.add(predecessorTezOp);
                    }
                }
            } else {
                int errCode = 2027;
                String msg = "Tez operator has been closed. This is unexpected for a merge.";
                throw new PlanException(msg, errCode, PigException.BUG);
            }
        }

        merge(ret.plan, toBeMerged);

        Iterator<TezOperator> it = toBeConnected.iterator();
        while (it.hasNext()) {
            tezPlan.connect(it.next(), ret);
        }
        for (TezOperator tezOp : toBeRemoved) {
            if (tezOp.requestedParallelism > ret.requestedParallelism) {
                ret.requestedParallelism = tezOp.requestedParallelism;
            }
            for (String udf : tezOp.UDFs) {
                if (!ret.UDFs.contains(udf)) {
                    ret.UDFs.add(udf);
                }
            }
            // We also need to change scalar marking
            for (PhysicalOperator physOp : tezOp.scalars) {
                if (!ret.scalars.contains(physOp)) {
                    ret.scalars.add(physOp);
                }
            }
            Set<PhysicalOperator> opsToChange = Sets.newHashSet();
            for (Map.Entry<PhysicalOperator, TezOperator> entry : phyToTezOpMap.entrySet()) {
                if (entry.getValue()==tezOp) {
                    opsToChange.add(entry.getKey());
                }
            }
            for (PhysicalOperator op : opsToChange) {
                phyToTezOpMap.put(op, ret);
            }

            tezPlan.remove(tezOp);
        }
        return ret;
    }

    /**
     * The merge of a list of plans into a single plan
     * @param <O>
     * @param <E>
     * @param finPlan - Final Plan into which the list of plans is merged
     * @param plans - list of plans to be merged
     * @throws PlanException
     */
    private <O extends Operator<?>, E extends OperatorPlan<O>> void merge(
            E finPlan, List<E> plans) throws PlanException {
        for (E e : plans) {
            finPlan.merge(e);
        }
    }

    private void processUDFs(PhysicalPlan plan) throws VisitorException {
        if (plan != null) {
            //Process Scalars (UDF with referencedOperators)
            ScalarPhyFinder scalarPhyFinder = new ScalarPhyFinder(plan);
            scalarPhyFinder.visit();
            curTezOp.scalars.addAll(scalarPhyFinder.getScalars());

            //Process UDFs
            udfFinder.setPlan(plan);
            udfFinder.visit();
            curTezOp.UDFs.addAll(udfFinder.getUDFs());
        }
    }

    // visit methods in alphabetical order

    @Override
    public void visitCollectedGroup(POCollectedGroup op) throws VisitorException {
        int errCode = 2034;
        String msg = "Cannot compile " + op.getClass().getSimpleName();
        throw new TezCompilerException(msg, errCode, PigException.BUG);
    }

    @Override
    public void visitCounter(POCounter op) throws VisitorException {
        int errCode = 2034;
        String msg = "Cannot compile " + op.getClass().getSimpleName();
        throw new TezCompilerException(msg, errCode, PigException.BUG);
    }

    @Override
    public void visitCross(POCross op) throws VisitorException {
        int errCode = 2034;
        String msg = "Cannot compile " + op.getClass().getSimpleName();
        throw new TezCompilerException(msg, errCode, PigException.BUG);
    }

    @Override
    public void visitDistinct(PODistinct op) throws VisitorException {
        try {
            POLocalRearrange lr = localRearrangeFactory.create();
            lr.setDistinct(true);
            lr.setAlias(op.getAlias());
            curTezOp.plan.addAsLeaf(lr);
            TezOperator lastOp = curTezOp;

            // Mark the start of a new TezOperator, connecting the inputs.
            blocking();
            TezCompilerUtil.setCustomPartitioner(op.getCustomPartitioner(), curTezOp);

            // Add the DISTINCT plan as the combine plan. In MR Pig, the combiner is implemented
            // with a global variable and a specific DistinctCombiner class. This seems better.
            PhysicalPlan combinePlan = curTezOp.inEdges.get(lastOp.getOperatorKey()).combinePlan;
            addDistinctPlan(combinePlan, 1);

            POLocalRearrangeTez clr = localRearrangeFactory.create();
            clr.setOutputKey(curTezOp.getOperatorKey().toString());
            clr.setDistinct(true);
            combinePlan.addAsLeaf(clr);

            addDistinctPlan(curTezOp.plan, op.getRequestedParallelism());
            phyToTezOpMap.put(op, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Cannot compile " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG);
        }
    }

    // Adds the plan for DISTINCT. Note that the PODistinct is not actually added to the plan, but
    // rather is implemented by the action of the local rearrange, shuffle and project operations.
    private void addDistinctPlan(PhysicalPlan plan, int rp) throws PlanException {
        POPackage pkg = getPackage(1, DataType.TUPLE);
        pkg.getPkgr().setDistinct(true);
        plan.addAsLeaf(pkg);

        POProject project = new POProject(new OperatorKey(scope, nig.getNextNodeId(scope)));
        project.setResultType(DataType.TUPLE);
        project.setStar(false);
        project.setColumn(0);
        project.setOverloaded(false);

        POForEach forEach = TezCompilerUtil.getForEach(project, rp, scope, nig);
        plan.addAsLeaf(forEach);
    }

    @Override
    public void visitFilter(POFilter op) throws VisitorException {
        try {
            if (curTezOp.isSplitSubPlan()) {
                // Do not add the filter. Refer NoopFilterRemover.java of MR
                PhysicalPlan filterPlan = op.getPlan();
                if (filterPlan.size() == 1) {
                    PhysicalOperator fp = filterPlan.getRoots().get(0);
                    if (fp instanceof ConstantExpression) {
                        ConstantExpression exp = (ConstantExpression)fp;
                        Object value = exp.getValue();
                        if (value instanceof Boolean) {
                            Boolean filterValue = (Boolean)value;
                            if (filterValue) {
                                return;
                            }
                        }
                    }
                }
            }
            nonBlocking(op);
            processUDFs(op.getPlan());
            phyToTezOpMap.put(op, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitFRJoin(POFRJoin op) throws VisitorException {
        try {
            FileSpec[] replFiles = new FileSpec[op.getInputs().size()];
            for (int i = 0; i < replFiles.length; i++) {
                if (i == op.getFragment()) {
                    continue;
                }
                replFiles[i] = getTempFileSpec();
            }
            op.setReplFiles(replFiles);

            List<String> inputKeys = Lists.newArrayList();
            curTezOp = phyToTezOpMap.get(op.getInputs().get(op.getFragment()));

            for (int i = 0; i < compiledInputs.length; i++) {
                TezOperator tezOp = compiledInputs[i];
                if (curTezOp.equals(tezOp)) {
                    continue;
                }

                if (!tezOp.isClosed()) {
                    POLocalRearrangeTez lr = new POLocalRearrangeTez(op.getLRs()[i]);
                    lr.setOutputKey(curTezOp.getOperatorKey().toString());

                    tezOp.plan.addAsLeaf(lr);
                    TezEdgeDescriptor edge = handleSplitAndConnect(tezPlan, tezOp, curTezOp);
                    if (tezOp.getSplitOperatorKey() == null) {
                        inputKeys.add(tezOp.getSplitOperatorKey().toString());
                    } else {
                        inputKeys.add(tezOp.getOperatorKey().toString());
                    }

                    // Configure broadcast edges for replicated tables
                    edge.dataMovementType = DataMovementType.BROADCAST;
                    edge.outputClassName = OnFileUnorderedKVOutput.class.getName();
                    edge.inputClassName = ShuffledUnorderedKVInput.class.getName();
                } else {
                    int errCode = 2022;
                    String msg = "The current operator is closed. This is unexpected while compiling.";
                    throw new TezCompilerException(msg, errCode, PigException.BUG);
                }
            }

            if (!curTezOp.isClosed()) {
                curTezOp.plan.addAsLeaf(new POFRJoinTez(op, inputKeys));
            } else {
                int errCode = 2022;
                String msg = "The current operator is closed. This is unexpected while compiling.";
                throw new TezCompilerException(msg, errCode, PigException.BUG);
            }

            List<List<PhysicalPlan>> joinPlans = op.getJoinPlans();
            if (joinPlans != null) {
                for (List<PhysicalPlan> joinPlan : joinPlans) {
                    if (joinPlan != null) {
                        for (PhysicalPlan plan : joinPlan) {
                            processUDFs(plan);
                        }
                    }
                }
            }
            phyToTezOpMap.put(op, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitLimit(POLimit op) throws VisitorException {
        try {
            if (op.getLimitPlan() != null) {
                processUDFs(op.getLimitPlan());
            }

            // As an optimization, we'll add a limit to the end of the last tezOp. Note that in
            // some cases, such as when we have ORDER BY followed by LIMIT, the LimitOptimizer has
            // already removed the POLimit from the physical plan.
            if (!pigContext.inIllustrator) {
                nonBlocking(op);
                phyToTezOpMap.put(op, curTezOp);
            }

            // If the parallelism of the current vertex is one and it doesn't do a LOAD (whose
            // parallelism is determined by the InputFormat), we don't need another vertex.
            if (curTezOp.requestedParallelism == 1) {
                boolean canStop = true;
                for (PhysicalOperator planOp : curTezOp.plan.getRoots()) {
                    if (planOp instanceof POLoad) {
                      canStop = false;
                      break;
                    }
                }
                if (canStop) {
                    return;
                }
            }

            // Need to add POLocalRearrange to the end of the last tezOp before we shuffle.
            POLocalRearrange lr = localRearrangeFactory.create();
            lr.setAlias(op.getAlias());
            curTezOp.plan.addAsLeaf(lr);

            // Mark the start of a new TezOperator, connecting the inputs.
            blocking();

            // As an optimization, don't do any sorting in the shuffle, as LIMIT does not make any
            // ordering guarantees.
            // TODO Enable this after TEZ-661
            // TezEdgeDescriptor edge = curTezOp.inEdges.get(lastOp.getOperatorKey());
            // edge.outputClassName = OnFileUnorderedKVOutput.class.getName();
            // edge.inputClassName = ShuffledUnorderedKVInput.class.getName();

            // Then add a POPackage and a POForEach to the start of the new tezOp.
            POPackage pkg = getPackage(1, DataType.TUPLE);
            POForEach forEach = TezCompilerUtil.getForEachPlain(scope, nig);
            pkg.setAlias(op.getAlias());
            forEach.setAlias(op.getAlias());
            curTezOp.plan.add(pkg);
            curTezOp.plan.addAsLeaf(forEach);

            if (!pigContext.inIllustrator) {
                POLimit limitCopy = new POLimit(new OperatorKey(scope, nig.getNextNodeId(scope)));
                limitCopy.setAlias(op.getAlias());
                limitCopy.setLimit(op.getLimit());
                limitCopy.setLimitPlan(op.getLimitPlan());
                curTezOp.plan.addAsLeaf(limitCopy);
            } else {
                curTezOp.plan.addAsLeaf(op);
            }

            // Explicitly set the parallelism for the new vertex to 1.
            curTezOp.requestedParallelism = 1;
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitLoad(POLoad op) throws VisitorException {
        try {
            nonBlocking(op);
            phyToTezOpMap.put(op, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitLocalRearrange(POLocalRearrange op) throws VisitorException {
        try{
            POLocalRearrange opTez = new POLocalRearrangeTez(op);
            nonBlocking(opTez);
            List<PhysicalPlan> plans = opTez.getPlans();
            if (plans != null) {
                for (PhysicalPlan ep : plans) {
                    processUDFs(ep);
                }
            }
            phyToTezOpMap.put(opTez, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitGlobalRearrange(POGlobalRearrange op) throws VisitorException {
        try {
            blocking();
            TezCompilerUtil.setCustomPartitioner(op.getCustomPartitioner(), curTezOp);
            phyToTezOpMap.put(op, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitMergeCoGroup(POMergeCogroup op) throws VisitorException {
        int errCode = 2034;
        String msg = "Cannot compile " + op.getClass().getSimpleName();
        throw new TezCompilerException(msg, errCode, PigException.BUG);
    }

    /** Since merge-join works on two inputs there are exactly two TezOper predecessors identified  as left and right.
     *  Right input generates index on-the-fly. This consists of two Tez vertexes. The first vertex generates index,
     *  and the second vertex sort them.
     *  Left input contains POMergeJoin which do the actual join.
     *  First right Tez oper is identified as rightTezOpr, second is identified as rightTezOpr2
     *  Left Tez oper is identified as curTezOper.

     *  1) RightTezOpr: It can be preceded only by POLoad. If there is anything else
     *                  in physical plan, that is yanked and set as inner plans of joinOp.
     *  2) LeftTezOper:  add the Join operator in it.
     *
     *  We also need to segment the DAG into two, because POMergeJoin depends on the index file which loads with
     *  DefaultIndexableLoader. It is possible to convert the index as a broadcast input, but that is costly
     *  because a lot of logic is built into DefaultIndexableLoader. We can revisit it later.
     */
    @Override
    public void visitMergeJoin(POMergeJoin joinOp) throws VisitorException {

        try{
            joinOp.setEndOfRecordMark(POStatus.STATUS_NULL);
            if(compiledInputs.length != 2 || joinOp.getInputs().size() != 2){
                int errCode=1101;
                throw new MRCompilerException("Merge Join must have exactly two inputs. Found : "+compiledInputs.length, errCode);
            }

            curTezOp = phyToTezOpMap.get(joinOp.getInputs().get(0));

            TezOperator rightTezOpr = null;
            TezOperator rightTezOprAggr = null;
            if(curTezOp.equals(compiledInputs[0]))
                rightTezOpr = compiledInputs[1];
            else
                rightTezOpr = compiledInputs[0];

            // We will first operate on right side which is indexer job.
            // First yank plan of the compiled right input and set that as an inner plan of right operator.
            PhysicalPlan rightPipelinePlan;
            if(!rightTezOpr.closed){
                PhysicalPlan rightPlan = rightTezOpr.plan;
                if(rightPlan.getRoots().size() != 1){
                    int errCode = 2171;
                    String errMsg = "Expected one but found more then one root physical operator in physical plan.";
                    throw new MRCompilerException(errMsg,errCode,PigException.BUG);
                }

                PhysicalOperator rightLoader = rightPlan.getRoots().get(0);
                if(! (rightLoader instanceof POLoad)){
                    int errCode = 2172;
                    String errMsg = "Expected physical operator at root to be POLoad. Found : "+rightLoader.getClass().getCanonicalName();
                    throw new MRCompilerException(errMsg,errCode);
                }

                if (rightPlan.getSuccessors(rightLoader) == null || rightPlan.getSuccessors(rightLoader).isEmpty())
                    // Load - Join case.
                    rightPipelinePlan = null;

                else{ // We got something on right side. Yank it and set it as inner plan of right input.
                    rightPipelinePlan = rightPlan.clone();
                    PhysicalOperator root = rightPipelinePlan.getRoots().get(0);
                    rightPipelinePlan.disconnect(root, rightPipelinePlan.getSuccessors(root).get(0));
                    rightPipelinePlan.remove(root);
                    rightPlan.trimBelow(rightLoader);
                }
            }
            else{
                int errCode = 2022;
                String msg = "Right input plan have been closed. This is unexpected while compiling.";
                throw new PlanException(msg, errCode, PigException.BUG);
            }

            joinOp.setupRightPipeline(rightPipelinePlan);

            // At this point, we must be operating on input plan of right input and it would contain nothing else other then a POLoad.
            POLoad rightLoader = (POLoad)rightTezOpr.plan.getRoots().get(0);
            joinOp.setSignature(rightLoader.getSignature());
            LoadFunc rightLoadFunc = rightLoader.getLoadFunc();
            List<String> udfs = new ArrayList<String>();
            if(IndexableLoadFunc.class.isAssignableFrom(rightLoadFunc.getClass())) {
                joinOp.setRightLoaderFuncSpec(rightLoader.getLFile().getFuncSpec());
                joinOp.setRightInputFileName(rightLoader.getLFile().getFileName());
                udfs.add(rightLoader.getLFile().getFuncSpec().toString());

                // we don't need the right TezOper since
                // the right loader is an IndexableLoadFunc which can handle the index
                // itself
                tezPlan.remove(rightTezOpr);
                if(rightTezOpr == compiledInputs[0]) {
                    compiledInputs[0] = null;
                } else if(rightTezOpr == compiledInputs[1]) {
                    compiledInputs[1] = null;
                }
                rightTezOpr = null;

                // validate that the join keys in merge join are only
                // simple column projections or '*' and not expression - expressions
                // cannot be handled when the index is built by the storage layer on the sorted
                // data when the sorted data (and corresponding index) is written.
                // So merge join will be restricted not have expressions as
                // join keys
                int numInputs = mPlan.getPredecessors(joinOp).size(); // should be 2
                for(int i = 0; i < numInputs; i++) {
                    List<PhysicalPlan> keyPlans = joinOp.getInnerPlansOf(i);
                    for (PhysicalPlan keyPlan : keyPlans) {
                        for(PhysicalOperator op : keyPlan) {
                            if(!(op instanceof POProject)) {
                                int errCode = 1106;
                                String errMsg = "Merge join is possible only for simple column or '*' join keys when using " +
                                rightLoader.getLFile().getFuncSpec() + " as the loader";
                                throw new MRCompilerException(errMsg, errCode, PigException.INPUT);
                            }
                        }
                    }
                }
            } else {
                LoadFunc loadFunc = rightLoader.getLoadFunc();
                //Replacing POLoad with indexer is disabled for 'merge-sparse' joins.  While
                //this feature would be useful, the current implementation of DefaultIndexableLoader
                //is not designed to handle multiple calls to seekNear.  Specifically, it rereads the entire index
                //for each call.  Some refactoring of this class is required - and then the check below could be removed.
                if (joinOp.getJoinType() == LOJoin.JOINTYPE.MERGESPARSE) {
                    int errCode = 1104;
                    String errMsg = "Right input of merge-join must implement IndexableLoadFunc. " +
                    "The specified loader " + loadFunc + " doesn't implement it";
                    throw new MRCompilerException(errMsg,errCode);
                }

                // Replace POLoad with  indexer.

                if (! (OrderedLoadFunc.class.isAssignableFrom(loadFunc.getClass()))){
                    int errCode = 1104;
                    String errMsg = "Right input of merge-join must implement " +
                    "OrderedLoadFunc interface. The specified loader "
                    + loadFunc + " doesn't implement it";
                    throw new MRCompilerException(errMsg,errCode);
                }

                String[] indexerArgs = new String[6];
                List<PhysicalPlan> rightInpPlans = joinOp.getInnerPlansOf(1);
                FileSpec origRightLoaderFileSpec = rightLoader.getLFile();

                indexerArgs[0] = origRightLoaderFileSpec.getFuncSpec().toString();
                indexerArgs[1] = ObjectSerializer.serialize((Serializable)rightInpPlans);
                indexerArgs[2] = ObjectSerializer.serialize(rightPipelinePlan);
                indexerArgs[3] = rightLoader.getSignature();
                indexerArgs[4] = rightLoader.getOperatorKey().scope;
                indexerArgs[5] = Boolean.toString(true);

                FileSpec lFile = new FileSpec(rightLoader.getLFile().getFileName(),new FuncSpec(MergeJoinIndexer.class.getName(), indexerArgs));
                rightLoader.setLFile(lFile);

                // Loader of operator will return a tuple of form -
                // (keyFirst1, keyFirst2, .. , position, splitIndex) See MergeJoinIndexer

                rightTezOprAggr = getTezOp();
                tezPlan.add(rightTezOprAggr);
                TezCompilerUtil.simpleConnectTwoVertex(tezPlan, rightTezOpr, rightTezOprAggr, scope, nig);
                rightTezOprAggr.requestedParallelism = 1; // we need exactly one task for indexing job.

                POStore st = TezCompilerUtil.getStore(scope, nig);
                FileSpec strFile = getTempFileSpec();
                st.setSFile(strFile);
                rightTezOprAggr.plan.addAsLeaf(st);
                rightTezOprAggr.setClosed(true);
                rightTezOprAggr.segmentBelow = true;

                // set up the DefaultIndexableLoader for the join operator
                String[] defaultIndexableLoaderArgs = new String[5];
                defaultIndexableLoaderArgs[0] = origRightLoaderFileSpec.getFuncSpec().toString();
                defaultIndexableLoaderArgs[1] = strFile.getFileName();
                defaultIndexableLoaderArgs[2] = strFile.getFuncSpec().toString();
                defaultIndexableLoaderArgs[3] = joinOp.getOperatorKey().scope;
                defaultIndexableLoaderArgs[4] = origRightLoaderFileSpec.getFileName();
                joinOp.setRightLoaderFuncSpec((new FuncSpec(DefaultIndexableLoader.class.getName(), defaultIndexableLoaderArgs)));
                joinOp.setRightInputFileName(origRightLoaderFileSpec.getFileName());

                joinOp.setIndexFile(strFile.getFileName());
                udfs.add(origRightLoaderFileSpec.getFuncSpec().toString());
            }

            // We are done with right side. Lets work on left now.
            // Join will be materialized in leftTezOper.
            if(!curTezOp.isClosed()) // Life is easy
                curTezOp.plan.addAsLeaf(joinOp);

            else{
                int errCode = 2022;
                String msg = "Input plan has been closed. This is unexpected while compiling.";
                throw new PlanException(msg, errCode, PigException.BUG);
            }
            if(rightTezOprAggr != null) {
                rightTezOprAggr.markIndexer();
                // We want to ensure indexing job runs prior to actual join job. So, connect them in order.
                TezCompilerUtil.connect(tezPlan, rightTezOprAggr, curTezOp);
            }
            phyToTezOpMap.put(joinOp, curTezOp);
            // no combination of small splits as there is currently no way to guarantee the sortness
            // of the combined splits.
            curTezOp.noCombineSmallSplits();
            curTezOp.UDFs.addAll(udfs);
        }
        catch(PlanException e){
            int errCode = 2034;
            String msg = "Error compiling operator " + joinOp.getClass().getCanonicalName();
            throw new MRCompilerException(msg, errCode, PigException.BUG, e);
        }
       catch (IOException e){
           int errCode = 3000;
           String errMsg = "IOException caught while compiling POMergeJoin";
            throw new MRCompilerException(errMsg, errCode,e);
        }
       catch(CloneNotSupportedException e){
           int errCode = 2127;
           String errMsg = "Cloning exception caught while compiling POMergeJoin";
           throw new MRCompilerException(errMsg, errCode, PigException.BUG, e);
       }
    }

    @Override
    public void visitNative(PONative op) throws VisitorException {
        int errCode = 2034;
        String msg = "Cannot compile " + op.getClass().getSimpleName();
        throw new TezCompilerException(msg, errCode, PigException.BUG);
    }

    @Override
    public void visitPackage(POPackage op) throws VisitorException{
        try{
            nonBlocking(op);
            phyToTezOpMap.put(op, curTezOp);
            if (op.getPkgr().getPackageType() == PackageType.UNION) {
                curTezOp.markUnion();
            } else if (op.getPkgr().getPackageType() == PackageType.JOIN) {
                curTezOp.markRegularJoin();
            } else if (op.getPkgr().getPackageType() == PackageType.GROUP) {
                if (op.getNumInps() == 1) {
                    curTezOp.markGroupBy();
                } else if (op.getNumInps() > 1) {
                    curTezOp.markCogroup();
                }
            }
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitPOForEach(POForEach op) throws VisitorException{
        try{
            nonBlocking(op);
            List<PhysicalPlan> plans = op.getInputPlans();
            if (plans != null) {
                for (PhysicalPlan ep : plans) {
                    processUDFs(ep);
                }
            }
            phyToTezOpMap.put(op, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitRank(PORank op) throws VisitorException {
        int errCode = 2034;
        String msg = "Cannot compile " + op.getClass().getSimpleName();
        throw new TezCompilerException(msg, errCode, PigException.BUG);
    }

    @Override
    public void visitSkewedJoin(POSkewedJoin op) throws VisitorException {
        //TODO: handle split and connect
        try {
            // LR that transfers loaded input to partition vertex
            POLocalRearrangeTez lrTez = localRearrangeFactory.create(LocalRearrangeType.NULL);
            // LR that broadcasts sampled input to sampling aggregation vertex
            POLocalRearrangeTez lrTezSample = localRearrangeFactory.create(LocalRearrangeType.NULL);

            int sampleRate = POPoissonSample.DEFAULT_SAMPLE_RATE;
            if (pigProperties.containsKey(PigConfiguration.SAMPLE_RATE)) {
                sampleRate = Integer.valueOf(pigProperties.getProperty(PigConfiguration.SAMPLE_RATE));
            }
            float heapPerc =  PartitionSkewedKeys.DEFAULT_PERCENT_MEMUSAGE;
            if (pigProperties.containsKey(PigConfiguration.PERC_MEM_AVAIL)) {
                heapPerc = Float.valueOf(pigProperties.getProperty(PigConfiguration.PERC_MEM_AVAIL));
            }
            POPoissonSample poSample = new POPoissonSample(new OperatorKey(scope,nig.getNextNodeId(scope)),
                    -1, sampleRate, heapPerc);

            TezOperator prevOp = compiledInputs[0];
            prevOp.plan.addAsLeaf(lrTez);
            prevOp.plan.addAsLeaf(poSample);

            MultiMap<PhysicalOperator, PhysicalPlan> joinPlans = op.getJoinPlans();
            List<PhysicalOperator> l = plan.getPredecessors(op);
            List<PhysicalPlan> groups = joinPlans.get(l.get(0));
            List<Boolean> ascCol = new ArrayList<Boolean>();
            for (int i=0; i< groups.size(); i++) {
                ascCol.add(false);
            }

            // Set up transform plan to get keys and memory size of input
            // tuples. It first adds all the plans to get key columns.
            List<PhysicalPlan> transformPlans = new ArrayList<PhysicalPlan>();
            transformPlans.addAll(groups);

            // Then it adds a column for memory size
            POProject prjStar = new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));
            prjStar.setResultType(DataType.TUPLE);
            prjStar.setStar(true);

            List<PhysicalOperator> ufInps = new ArrayList<PhysicalOperator>();
            ufInps.add(prjStar);

            PhysicalPlan ep = new PhysicalPlan();
            POUserFunc uf = new POUserFunc(new OperatorKey(scope,nig.getNextNodeId(scope)),
                    -1, ufInps, new FuncSpec(GetMemNumRows.class.getName(), (String[])null));
            uf.setResultType(DataType.TUPLE);
            ep.add(uf);
            ep.add(prjStar);
            ep.connect(prjStar, uf);

            transformPlans.add(ep);

            List<Boolean> flat1 = new ArrayList<Boolean>();
            List<PhysicalPlan> eps1 = new ArrayList<PhysicalPlan>();

            for (int i=0; i<transformPlans.size(); i++) {
                eps1.add(transformPlans.get(i));
                flat1.add(true);
            }

            // This foreach will pick the sort key columns from the POPoissonSample output
            POForEach nfe1 = new POForEach(new OperatorKey(scope,nig.getNextNodeId(scope)),
                    -1, eps1, flat1);
            prevOp.plan.addAsLeaf(nfe1);
            prevOp.plan.addAsLeaf(lrTezSample);
            prevOp.setClosed(true);

            POSort sort = new POSort(op.getOperatorKey(), op.getRequestedParallelism(),
                    null, groups, ascCol, null);
            String per = pigProperties.getProperty("pig.skewedjoin.reduce.memusage",
                    String.valueOf(PartitionSkewedKeys.DEFAULT_PERCENT_MEMUSAGE));
            String mc = pigProperties.getProperty("pig.skewedjoin.reduce.maxtuple", "0");

            int rp = Math.max(op.getRequestedParallelism(), 1);
            Pair<TezOperator, Integer> sampleJobPair = getSamplingAggregationJobs(sort, rp, null,
                    PartitionSkewedKeys.class.getName(), new String[]{per, mc}, 2);
            rp = sampleJobPair.second;

            // Set parallelism of SkewedJoin as the value calculated by sampling
            // job if "parallel" is specified in join statement, "rp" is equal
            // to that number if not specified, use the value that sampling
            // process calculated based on default.
            op.setRequestedParallelism(rp);

            TezOperator[] joinJobs = new TezOperator[] {null, compiledInputs[1], null};
            TezOperator[] joinInputs = new TezOperator[] {compiledInputs[0], compiledInputs[1]};
            TezOperator[] rearrangeOutputs = new TezOperator[2];

            compiledInputs = new TezOperator[] {joinInputs[0]};

            blocking();

            // Then add a POPackage and a POForEach to the start of the new tezOp.
            POPackage pkg = getPackage(1, DataType.BYTEARRAY);
            curTezOp.plan.add(pkg);

            POProject project = new POProject(new OperatorKey(scope, nig.getNextNodeId(scope)));
            project.setResultType(DataType.BAG);
            project.setStar(false);
            project.setColumn(1);
            POForEach forEach =
                    TezCompilerUtil.getForEach(project, sort.getRequestedParallelism(), scope, nig);
            curTezOp.plan.addAsLeaf(forEach);
            joinJobs[0] = curTezOp;

            // Run POLocalRearrange for first join table
            POLocalRearrangeTez lr =
                    new POLocalRearrangeTez(new OperatorKey(scope,nig.getNextNodeId(scope)), rp);
            try {
                lr.setIndex(0);
            } catch (ExecException e) {
                int errCode = 2058;
                String msg = "Unable to set index on newly created POLocalRearrange.";
                throw new PlanException(msg, errCode, PigException.BUG, e);
            }

            // Check the type of group keys, if there are more than one field, the key is TUPLE.
            byte type = DataType.TUPLE;
            if (groups.size() == 1) {
                type = groups.get(0).getLeaves().get(0).getResultType();
            }

            // Run POLocalRearrange for first join table
            lr.setKeyType(type);
            lr.setPlans(groups);
            lr.setSkewedJoin(true);
            lr.setResultType(DataType.TUPLE);
            joinJobs[0].plan.addAsLeaf(lr);
            joinJobs[0].setClosed(true);
            if (lr.getRequestedParallelism() > joinJobs[0].requestedParallelism) {
                joinJobs[0].requestedParallelism = lr.getRequestedParallelism();
            }
            rearrangeOutputs[0] = joinJobs[0];

            compiledInputs = new TezOperator[] {joinInputs[1]};

            // Run POPartitionRearrange for second join table
            POPartitionRearrangeTez pr =
                    new POPartitionRearrangeTez(new OperatorKey(scope, nig.getNextNodeId(scope)), rp);
            try {
                pr.setIndex(1);
            } catch (ExecException e) {
                int errCode = 2058;
                String msg = "Unable to set index on newly created POPartitionRearrange.";
                throw new PlanException(msg, errCode, PigException.BUG, e);
            }

            groups = joinPlans.get(l.get(1));
            pr.setPlans(groups);
            pr.setKeyType(type);
            pr.setSkewedJoin(true);
            pr.setResultType(DataType.BAG);
            joinJobs[1].plan.addAsLeaf(pr);
            joinJobs[1].setClosed(true);
            if (pr.getRequestedParallelism() > joinJobs[1].requestedParallelism) {
                joinJobs[1].requestedParallelism = pr.getRequestedParallelism();
            }
            rearrangeOutputs[1] = joinJobs[1];

            compiledInputs = rearrangeOutputs;

            // Create POGlobalRearrange
            POGlobalRearrange gr =
                    new POGlobalRearrange(new OperatorKey(scope, nig.getNextNodeId(scope)), rp);
            // Skewed join has its own special partitioner
            gr.setResultType(DataType.TUPLE);
            gr.visit(this);
            joinJobs[2] = curTezOp;
            if (gr.getRequestedParallelism() > joinJobs[2].requestedParallelism) {
                joinJobs[2].requestedParallelism = gr.getRequestedParallelism();
            }

            compiledInputs = new TezOperator[] {joinJobs[2]};

            // Create POPakcage
            pkg = getPackage(2, DataType.TUPLE);
            boolean [] inner = op.getInnerFlags();
            pkg.getPkgr().setInner(inner);
            pkg.visit(this);

            // Create POForEach
            List<PhysicalPlan> eps = new ArrayList<PhysicalPlan>();
            List<Boolean> flat = new ArrayList<Boolean>();

            // Add corresponding POProjects
            for (int i=0; i < 2; i++) {
                ep = new PhysicalPlan();
                POProject prj = new POProject(new OperatorKey(scope, nig.getNextNodeId(scope)));
                prj.setColumn(i+1);
                prj.setOverloaded(false);
                prj.setResultType(DataType.BAG);
                ep.add(prj);
                eps.add(ep);
                if (!inner[i]) {
                    // Add an empty bag for outer join
                    CompilerUtils.addEmptyBagOuterJoin(ep, op.getSchema(i));
                }
                flat.add(true);
            }

            POForEach fe =
                    new POForEach(new OperatorKey(scope, nig.getNextNodeId(scope)), -1, eps, flat);
            fe.setResultType(DataType.TUPLE);
            fe.visit(this);

            // Connect vertices
            lrTez.setOutputKey(joinJobs[0].getOperatorKey().toString());
            lrTezSample.setOutputKey(sampleJobPair.first.getOperatorKey().toString());

            TezEdgeDescriptor edge = joinJobs[0].inEdges.get(prevOp.getOperatorKey());
            // TODO: Convert to unsorted shuffle after TEZ-661
            // edge.outputClassName = OnFileUnorderedKVOutput.class.getName();
            // edge.inputClassName = ShuffledUnorderedKVInput.class.getName();
            edge.partitionerClass = RoundRobinPartitioner.class;

            TezCompilerUtil.connect(tezPlan, prevOp, sampleJobPair.first);

            POSplit split = (POSplit) sampleJobPair.first.plan.getLeaves().get(0);
            List<PhysicalPlan> pp = split.getPlans();
            for (int i = 0; i < pp.size(); i++) {
                TezCompilerUtil.connect(tezPlan, sampleJobPair.first, joinJobs[i]);

                // Configure broadcast edges for distribution map
                edge = joinJobs[i].inEdges.get(sampleJobPair.first.getOperatorKey());
                edge.dataMovementType = DataMovementType.BROADCAST;
                edge.outputClassName = OnFileUnorderedKVOutput.class.getName();
                edge.inputClassName = ShuffledUnorderedKVInput.class.getName();
                lrTez = (POLocalRearrangeTez) pp.get(i).getLeaves().get(0);
                lrTez.setOutputKey(joinJobs[i].getOperatorKey().toString());
                joinJobs[i].sampleOperator = sampleJobPair.first;

                // Configure skewed partitioner for join
                edge = joinJobs[2].inEdges.get(joinJobs[i].getOperatorKey());
                edge.partitionerClass = SkewedPartitionerTez.class;
            }
            joinJobs[2].setSkewedJoin(true);

            phyToTezOpMap.put(op, curTezOp);
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    /**
     * Returns a temporary DFS Path
     * @return
     * @throws IOException
     */
    private FileSpec getTempFileSpec() throws IOException {
        return new FileSpec(FileLocalizer.getTemporaryPath(pigContext).toString(),
                new FuncSpec(Utils.getTmpFileCompressorName(pigContext)));
    }

    /**
     * Force an end to the current vertex with store and sample
     * @return Tez operator that now is finished with a store.
     * @throws PlanException
     */
    private TezOperator endSingleInputWithStoreAndSample(POSort sort, POLocalRearrangeTez lr,
            POLocalRearrangeTez lrSample) throws PlanException {
        if(compiledInputs.length>1) {
            int errCode = 2023;
            String msg = "Received a multi input plan when expecting only a single input one.";
            throw new PlanException(msg, errCode, PigException.BUG);
        }
        TezOperator oper = compiledInputs[0];
        if (!oper.isClosed()) {
            lr.setOutputKey(curTezOp.getOperatorKey().toString());
            oper.plan.addAsLeaf(lr);

            List<Boolean> flat1 = new ArrayList<Boolean>();
            List<PhysicalPlan> eps1 = new ArrayList<PhysicalPlan>();

            Pair<POProject, Byte>[] sortProjs = null;
            try{
                sortProjs = getSortCols(sort.getSortPlans());
            }catch(Exception e) {
                throw new RuntimeException(e);
            }
            // Set up the projections of the key columns
            if (sortProjs == null) {
                PhysicalPlan ep = new PhysicalPlan();
                POProject prj = new POProject(new OperatorKey(scope,
                    nig.getNextNodeId(scope)));
                prj.setStar(true);
                prj.setOverloaded(false);
                prj.setResultType(DataType.TUPLE);
                ep.add(prj);
                eps1.add(ep);
                flat1.add(false);
            } else {
                for (Pair<POProject, Byte> sortProj : sortProjs) {
                    // Check for proj being null, null is used by getSortCols for a non POProject
                    // operator. Since Order by does not allow expression operators,
                    //it should never be set to null
                    if(sortProj == null){
                        int errCode = 2174;
                        String msg = "Internal exception. Could not create a sampler job";
                        throw new PlanException(msg, errCode, PigException.BUG);
                    }
                    PhysicalPlan ep = new PhysicalPlan();
                    POProject prj;
                    try {
                        prj = sortProj.first.clone();
                    } catch (CloneNotSupportedException e) {
                        //should not get here
                        throw new AssertionError(
                                "Error cloning project caught exception" + e
                        );
                    }
                    ep.add(prj);
                    eps1.add(ep);
                    flat1.add(false);
                }
            }

            // This foreach will pick the sort key columns from the RandomSampleLoader output
            POForEach nfe1 = new POForEach(new OperatorKey(scope,nig.getNextNodeId(scope)),-1,eps1,flat1);
            oper.plan.addAsLeaf(nfe1);

            POReservoirSample poSample = new POReservoirSample(new OperatorKey(scope,nig.getNextNodeId(scope)),
                    -1, null, 100);
            oper.plan.addAsLeaf(poSample);
            lrSample.setOutputKey(curTezOp.getOperatorKey().toString());
            oper.plan.addAsLeaf(lrSample);
        } else {
            int errCode = 2022;
            String msg = "The current operator is closed. This is unexpected while compiling.";
            throw new PlanException(msg, errCode, PigException.BUG);
        }
        return oper;
    }

    private Pair<TezOperator,Integer> getQuantileJobs(
            POSort inpSort,
            int rp) throws PlanException, VisitorException, ExecException {

        POSort sort = new POSort(inpSort.getOperatorKey(), inpSort
                .getRequestedParallelism(), null, inpSort.getSortPlans(),
                inpSort.getMAscCols(), inpSort.getMSortFunc());
        sort.addOriginalLocation(inpSort.getAlias(), inpSort.getOriginalLocations());

        // Turn the asc/desc array into an array of strings so that we can pass it
        // to the FindQuantiles function.
        List<Boolean> ascCols = inpSort.getMAscCols();
        String[] ascs = new String[ascCols.size()];
        for (int i = 0; i < ascCols.size(); i++) ascs[i] = ascCols.get(i).toString();
        // check if user defined comparator is used in the sort, if so
        // prepend the name of the comparator as the first fields in the
        // constructor args array to the FindQuantiles udf
        String[] ctorArgs = ascs;
        if(sort.isUDFComparatorUsed) {
            String userComparatorFuncSpec = sort.getMSortFunc().getFuncSpec().toString();
            ctorArgs = new String[ascs.length + 1];
            ctorArgs[0] = USER_COMPARATOR_MARKER + userComparatorFuncSpec;
            for(int j = 0; j < ascs.length; j++) {
                ctorArgs[j+1] = ascs[j];
            }
        }

        return getSamplingAggregationJobs(sort, rp, null, FindQuantiles.class.getName(), ctorArgs, 1);
    }

    /**
     * Create a sampling job to collect statistics by sampling input data. The
     * sequence of operations is as following:
     * <li>Add an extra field &quot;all&quot; into the tuple </li>
     * <li>Package all tuples into one bag </li>
     * <li>Add constant field for number of reducers. </li>
     * <li>Sorting the bag </li>
     * <li>Invoke UDF with the number of reducers and the sorted bag.</li>
     * <li>Data generated by UDF is transferred via a broadcast edge.</li>
     *
     * @param sort  the POSort operator used to sort the bag
     * @param rp  configured parallemism
     * @param sortKeyPlans  PhysicalPlans to be set into POSort operator to get sorting keys
     * @param udfClassName  the class name of UDF
     * @param udfArgs   the arguments of UDF
     * @return pair<tezoperator[],integer>
     * @throws PlanException
     * @throws VisitorException
     * @throws ExecException
     */
    private Pair<TezOperator,Integer> getSamplingAggregationJobs(POSort sort, int rp,
            List<PhysicalPlan> sortKeyPlans, String udfClassName, String[] udfArgs, int numLRs)
                    throws PlanException, VisitorException, ExecException {

        TezOperator oper = getTezOp();
        tezPlan.add(oper);

        POPackage pkg = getPackage(1, DataType.BYTEARRAY);
        oper.plan.add(pkg);

        // Lets start building the plan which will have the sort
        // for the foreach
        PhysicalPlan fe2Plan = new PhysicalPlan();
        // Top level project which just projects the tuple which is coming
        // from the foreach after the package
        POProject topPrj = new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));
        topPrj.setColumn(1);
        topPrj.setResultType(DataType.BAG);
        topPrj.setOverloaded(true);
        fe2Plan.add(topPrj);

        // the projections which will form sort plans
        List<PhysicalPlan> nesSortPlanLst = new ArrayList<PhysicalPlan>();
        if (sortKeyPlans != null) {
            for(int i=0; i<sortKeyPlans.size(); i++) {
                nesSortPlanLst.add(sortKeyPlans.get(i));
            }
        }else{
            Pair<POProject, Byte>[] sortProjs = null;
            try{
                sortProjs = getSortCols(sort.getSortPlans());
            }catch(Exception e) {
                throw new RuntimeException(e);
            }
            // Set up the projections of the key columns
            if (sortProjs == null) {
                PhysicalPlan ep = new PhysicalPlan();
                POProject prj = new POProject(new OperatorKey(scope,
                    nig.getNextNodeId(scope)));
                prj.setStar(true);
                prj.setOverloaded(false);
                prj.setResultType(DataType.TUPLE);
                ep.add(prj);
                nesSortPlanLst.add(ep);
            } else {
                for (int i=0; i<sortProjs.length; i++) {
                    POProject prj =
                        new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));

                    prj.setResultType(sortProjs[i].second);
                    if(sortProjs[i].first != null && sortProjs[i].first.isProjectToEnd()){
                        if(i != sortProjs.length -1){
                            //project to end has to be the last sort column
                            throw new AssertionError("Project-range to end (x..)" +
                            " is supported in order-by only as last sort column");
                        }
                        prj.setProjectToEnd(i);
                        break;
                    }
                    else{
                        prj.setColumn(i);
                    }
                    prj.setOverloaded(false);

                    PhysicalPlan ep = new PhysicalPlan();
                    ep.add(prj);
                    nesSortPlanLst.add(ep);
                }
            }
        }

        sort.setSortPlans(nesSortPlanLst);
        sort.setResultType(DataType.BAG);
        fe2Plan.add(sort);
        fe2Plan.connect(topPrj, sort);

        // The plan which will have a constant representing the
        // degree of parallelism for the final order by map-reduce job
        // this will either come from a "order by parallel x" in the script
        // or will be the default number of reducers for the cluster if
        // "parallel x" is not used in the script
        PhysicalPlan rpep = new PhysicalPlan();
        ConstantExpression rpce = new ConstantExpression(new OperatorKey(scope,nig.getNextNodeId(scope)));
        rpce.setRequestedParallelism(rp);

        // We temporarily set it to rp and will adjust it at runtime, because the final degree of parallelism
        // is unknown until we are ready to submit it. See PIG-2779.
        rpce.setValue(rp);

        rpce.setResultType(DataType.INTEGER);
        rpep.add(rpce);

        List<PhysicalPlan> genEps = new ArrayList<PhysicalPlan>();
        genEps.add(rpep);
        genEps.add(fe2Plan);

        List<Boolean> flattened2 = new ArrayList<Boolean>();
        flattened2.add(false);
        flattened2.add(false);

        POForEach nfe2 = new POForEach(new OperatorKey(scope,nig.getNextNodeId(scope)),-1, genEps, flattened2);
        oper.plan.add(nfe2);
        oper.plan.connect(pkg, nfe2);

        // Let's connect the output from the foreach containing
        // number of quantiles and the sorted bag of samples to
        // another foreach with the FindQuantiles udf. The input
        // to the FindQuantiles udf is a project(*) which takes the
        // foreach input and gives it to the udf
        PhysicalPlan ep4 = new PhysicalPlan();
        POProject prjStar4 = new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));
        prjStar4.setResultType(DataType.TUPLE);
        prjStar4.setStar(true);
        ep4.add(prjStar4);

        List<PhysicalOperator> ufInps = new ArrayList<PhysicalOperator>();
        ufInps.add(prjStar4);

        POUserFunc uf = new POUserFunc(new OperatorKey(scope,nig.getNextNodeId(scope)), -1, ufInps,
            new FuncSpec(udfClassName, udfArgs));
        ep4.add(uf);
        ep4.connect(prjStar4, uf);

        List<PhysicalPlan> ep4s = new ArrayList<PhysicalPlan>();
        ep4s.add(ep4);
        List<Boolean> flattened3 = new ArrayList<Boolean>();
        flattened3.add(false);
        POForEach nfe3 = new POForEach(new OperatorKey(scope,nig.getNextNodeId(scope)), -1, ep4s, flattened3);

        oper.plan.add(nfe3);
        oper.plan.connect(nfe2, nfe3);

        if (numLRs > 1) {
            // Skewed join broadcast sample map to multiple vertices, so we need
            // to add POSplit to the plan and attach LRs to POSplit.
            POSplit split = new POSplit(new OperatorKey(scope,nig.getNextNodeId(scope)));
            oper.setSplitOperatorKey(split.getOperatorKey());
            oper.plan.add(split);
            oper.plan.connect(nfe3, split);
            splitsSeen.put(split.getOperatorKey(), oper);

            for (int i = 0; i < numLRs; i++) {
                POLocalRearrangeTez lr = localRearrangeFactory.create(LocalRearrangeType.NULL);
                PhysicalPlan pp = new PhysicalPlan();
                pp.add(lr);
                split.addPlan(pp);
            }
        } else {
            POLocalRearrangeTez lr = localRearrangeFactory.create(LocalRearrangeType.NULL);
            oper.plan.add(lr);
            oper.plan.connect(nfe3, lr);
        }
        oper.setClosed(true);

        oper.requestedParallelism = 1;
        oper.markSampler();
        return new Pair<TezOperator, Integer>(oper, rp);
    }

    private static class FindKeyTypeVisitor extends PhyPlanVisitor {

        byte keyType = DataType.UNKNOWN;

        FindKeyTypeVisitor(PhysicalPlan plan) {
            super(plan,
                new DepthFirstWalker<PhysicalOperator, PhysicalPlan>(plan));
        }

        @Override
        public void visitProject(POProject p) throws VisitorException {
            keyType = p.getResultType();
        }
    }

    public static Pair<POProject,Byte> [] getSortCols(List<PhysicalPlan> plans) throws PlanException {
        if(plans!=null){
            @SuppressWarnings("unchecked")
            Pair<POProject,Byte>[] ret = new Pair[plans.size()];
            int i=-1;
            for (PhysicalPlan plan : plans) {
                PhysicalOperator op = plan.getLeaves().get(0);
                POProject proj;
                if (op instanceof POProject) {
                    if (((POProject)op).isStar()) return null;
                    proj = (POProject)op;
                } else {
                    proj = null;
                }
                byte type = op.getResultType();
                ret[++i] = new Pair<POProject, Byte>(proj, type);
            }
            return ret;
        }
        int errCode = 2026;
        String msg = "No expression plan found in POSort.";
        throw new PlanException(msg, errCode, PigException.BUG);
    }

    private TezOperator[] getSortJobs(
            POSort sort,
            int rp,
            byte keyType,
            Pair<POProject, Byte>[] fields) throws PlanException{
        TezOperator[] opers = new TezOperator[2];
        TezOperator oper1 = getTezOp();
        tezPlan.add(oper1);
        opers[0] = oper1;
        oper1.requestedParallelism = rp;

        long limit = sort.getLimit();
        oper1.limit = limit;

        List<PhysicalPlan> eps1 = new ArrayList<PhysicalPlan>();

        POPackage pkg = getPackage(1, DataType.BYTEARRAY);
        oper1.plan.add(pkg);

        POProject project = new POProject(new OperatorKey(scope, nig.getNextNodeId(scope)));
        project.setResultType(DataType.BAG);
        project.setStar(false);
        project.setColumn(1);
        POForEach forEach = TezCompilerUtil.getForEach(project, sort.getRequestedParallelism(), scope, nig);
        oper1.plan.addAsLeaf(forEach);

        boolean[] sortOrder;

        List<Boolean> sortOrderList = sort.getMAscCols();
        if(sortOrderList != null) {
            sortOrder = new boolean[sortOrderList.size()];
            for(int i = 0; i < sortOrderList.size(); ++i) {
                sortOrder[i] = sortOrderList.get(i);
            }
            oper1.setSortOrder(sortOrder);
        }

        if (fields == null) {
            // This is project *
            PhysicalPlan ep = new PhysicalPlan();
            POProject prj = new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));
            prj.setStar(true);
            prj.setOverloaded(false);
            prj.setResultType(DataType.TUPLE);
            ep.add(prj);
            eps1.add(ep);
        } else {
            // Attach the sort plans to the local rearrange to get the
            // projection.
            eps1.addAll(sort.getSortPlans());
        }

        POLocalRearrangeTez lr = new POLocalRearrangeTez(new OperatorKey(scope,nig.getNextNodeId(scope)));
        try {
            lr.setIndex(0);
        } catch (ExecException e) {
            int errCode = 2058;
            String msg = "Unable to set index on newly created POLocalRearrange.";
            throw new PlanException(msg, errCode, PigException.BUG, e);
        }
        lr.setKeyType((fields == null || fields.length>1) ? DataType.TUPLE : keyType);
        lr.setPlans(eps1);
        lr.setResultType(DataType.TUPLE);
        lr.addOriginalLocation(sort.getAlias(), sort.getOriginalLocations());
        oper1.plan.addAsLeaf(lr);

        oper1.setClosed(true);

        TezOperator oper2 = getTezOp();
        oper2.setGlobalSort(true);
        opers[1] = oper2;
        tezPlan.add(oper2);
        lr.setOutputKey(oper2.getOperatorKey().toString());

        if (limit!=-1) {
            POPackage pkg_c = new POPackage(new OperatorKey(scope, nig.getNextNodeId(scope)));
            pkg_c.setPkgr(new LitePackager());
            pkg_c.getPkgr().setKeyType((fields == null || fields.length > 1) ? DataType.TUPLE : keyType);
            pkg_c.setNumInps(1);
            oper2.inEdges.put(oper1.getOperatorKey(), new TezEdgeDescriptor());
            PhysicalPlan combinePlan = oper2.inEdges.get(oper1.getOperatorKey()).combinePlan;

            combinePlan.add(pkg_c);

            List<PhysicalPlan> eps_c1 = new ArrayList<PhysicalPlan>();
            List<Boolean> flat_c1 = new ArrayList<Boolean>();
            PhysicalPlan ep_c1 = new PhysicalPlan();
            POProject prj_c1 = new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));
            prj_c1.setColumn(1);
            prj_c1.setOverloaded(false);
            prj_c1.setResultType(DataType.BAG);
            ep_c1.add(prj_c1);
            eps_c1.add(ep_c1);
            flat_c1.add(true);
            POForEach fe_c1 = new POForEach(new OperatorKey(scope,nig.getNextNodeId(scope)),
                    -1, eps_c1, flat_c1);
            fe_c1.setResultType(DataType.TUPLE);

            combinePlan.addAsLeaf(fe_c1);

            POLimit pLimit = new POLimit(new OperatorKey(scope,nig.getNextNodeId(scope)));
            pLimit.setLimit(limit);
            combinePlan.addAsLeaf(pLimit);

            List<PhysicalPlan> eps_c2 = new ArrayList<PhysicalPlan>();
            eps_c2.addAll(sort.getSortPlans());

            POLocalRearrangeTez lr_c2 = new POLocalRearrangeTez(new OperatorKey(scope,nig.getNextNodeId(scope)));
            lr_c2.setOutputKey(oper2.getOperatorKey().toString());
            try {
                lr_c2.setIndex(0);
            } catch (ExecException e) {
                int errCode = 2058;
                String msg = "Unable to set index on newly created POLocalRearrange.";
                throw new PlanException(msg, errCode, PigException.BUG, e);
            }
            lr_c2.setKeyType((fields.length>1) ? DataType.TUPLE : keyType);
            lr_c2.setPlans(eps_c2);
            lr_c2.setResultType(DataType.TUPLE);
            combinePlan.addAsLeaf(lr_c2);
        }

        pkg = new POPackage(new OperatorKey(scope, nig.getNextNodeId(scope)));
        pkg.setPkgr(new LitePackager());
        pkg.getPkgr().setKeyType((fields == null || fields.length > 1) ? DataType.TUPLE : keyType);
        pkg.setNumInps(1);
        oper2.plan.add(pkg);

        PhysicalPlan ep = new PhysicalPlan();
        POProject prj = new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));
        prj.setColumn(1);
        prj.setOverloaded(false);
        prj.setResultType(DataType.BAG);
        ep.add(prj);
        List<PhysicalPlan> eps2 = new ArrayList<PhysicalPlan>();
        eps2.add(ep);
        List<Boolean> flattened = new ArrayList<Boolean>();
        flattened.add(true);
        POForEach nfe1 = new POForEach(new OperatorKey(scope,nig.getNextNodeId(scope)),-1,eps2,flattened);
        oper2.plan.add(nfe1);
        oper2.plan.connect(pkg, nfe1);
        if (limit!=-1) {
            POLimit pLimit2 = new POLimit(new OperatorKey(scope,nig.getNextNodeId(scope)));
            pLimit2.setLimit(limit);
            oper2.plan.addAsLeaf(pLimit2);
        }

        return opers;
    }

    @Override
    public void visitSort(POSort op) throws VisitorException {
        try{
            Pair<POProject, Byte>[] fields = getSortCols(op.getSortPlans());
            byte keyType = DataType.UNKNOWN;

            try {
                FindKeyTypeVisitor fktv =
                    new FindKeyTypeVisitor(op.getSortPlans().get(0));
                fktv.visit();
                keyType = fktv.keyType;
            } catch (VisitorException ve) {
                int errCode = 2035;
                String msg = "Internal error. Could not compute key type of sort operator.";
                throw new PlanException(msg, errCode, PigException.BUG, ve);
            }

            POLocalRearrangeTez lr = localRearrangeFactory.create(LocalRearrangeType.NULL);
            POLocalRearrangeTez lrSample = localRearrangeFactory.create(LocalRearrangeType.NULL);

            TezOperator prevOper = endSingleInputWithStoreAndSample(op, lr, lrSample);

            int rp = Math.max(op.getRequestedParallelism(), 1);

            Pair<TezOperator, Integer> quantJobParallelismPair = getQuantileJobs(op, rp);
            TezOperator[] sortOpers = getSortJobs(op, quantJobParallelismPair.second, keyType, fields);

            TezEdgeDescriptor edge = handleSplitAndConnect(tezPlan, prevOper, sortOpers[0]);

            // TODO: Convert to unsorted shuffle after TEZ-661
            // edge.outputClassName = OnFileUnorderedKVOutput.class.getName();
            // edge.inputClassName = ShuffledUnorderedKVInput.class.getName();
            edge.partitionerClass = RoundRobinPartitioner.class;

            handleSplitAndConnect(tezPlan, prevOper, quantJobParallelismPair.first, false);
            lr.setOutputKey(sortOpers[0].getOperatorKey().toString());
            lrSample.setOutputKey(quantJobParallelismPair.first.getOperatorKey().toString());

            edge = TezCompilerUtil.connect(tezPlan, quantJobParallelismPair.first, sortOpers[0]);
            edge.dataMovementType = DataMovementType.BROADCAST;
            edge.outputClassName = OnFileUnorderedKVOutput.class.getName();
            edge.inputClassName = ShuffledUnorderedKVInput.class.getName();
            lr = (POLocalRearrangeTez)quantJobParallelismPair.first.plan.getLeaves().get(0);
            lr.setOutputKey(sortOpers[0].getOperatorKey().toString());
            sortOpers[0].sampleOperator = quantJobParallelismPair.first;

            edge = TezCompilerUtil.connect(tezPlan, sortOpers[0], sortOpers[1]);
            edge.partitionerClass = WeightedRangePartitionerTez.class;

            curTezOp = sortOpers[1];

            // TODO: Review sort udf
//            if(op.isUDFComparatorUsed){
//                curTezOp.UDFs.add(op.getMSortFunc().getFuncSpec().toString());
//                curTezOp.isUDFComparatorUsed = true;
//            }
            phyToTezOpMap.put(op, curTezOp);
        }catch(Exception e){
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitSplit(POSplit op) throws VisitorException {
        try {
            if (splitsSeen.containsKey(op.getOperatorKey())) {
                // Since the plan for this split already exists in the tez plan,
                // discard the hierarchy or tez operators we constructed so far
                // till we encountered the split in this tree
                removeDupOpTreeOfSplit(tezPlan, curTezOp);
                curTezOp = startNew(op.getOperatorKey());
            } else {
                nonBlocking(op);
                if(curTezOp.isSplitSubPlan()) {
                    // Split followed by another split
                    // Set inputs to null as POSplit will attach input to roots
                    for (PhysicalOperator root : curTezOp.plan.getRoots()) {
                        root.setInputs(null);
                    }
                    TezOperator splitOp = splitsSeen.get(curTezOp.getSplitOperatorKey());
                    POSplit split = findPOSplit(splitOp, curTezOp.getSplitOperatorKey());
                    split.addPlan(curTezOp.plan);
                    addSubPlanPropertiesToParent(splitOp, curTezOp);
                    splitsSeen.put(op.getOperatorKey(), splitOp);
                    phyToTezOpMap.put(op, splitOp);
                } else {
                    splitsSeen.put(op.getOperatorKey(), curTezOp);
                    phyToTezOpMap.put(op, curTezOp);
                }
                curTezOp = startNew(op.getOperatorKey());
            }
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator "
                    + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }

    }

    @Override
    public void visitStore(POStore op) throws VisitorException {
        try {
            POStoreTez store = new POStoreTez(op);
            nonBlocking(store);
            phyToTezOpMap.put(op, curTezOp);
            if (store.getSFile()!=null && store.getSFile().getFuncSpec()!=null)
                curTezOp.UDFs.add(store.getSFile().getFuncSpec().toString());
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitStream(POStream op) throws VisitorException {
        try {
            nonBlocking(op);
            phyToTezOpMap.put(op, curTezOp);
        } catch(Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }

    @Override
    public void visitUnion(POUnion op) throws VisitorException {
        try {
            // Need to add POLocalRearrange to the end of each previous tezOp
            // before we broadcast.
            for (int i = 0; i < compiledInputs.length; i++) {
                POLocalRearrangeTez lr = localRearrangeFactory.create(i, LocalRearrangeType.STAR);
                lr.setAlias(op.getAlias());
                lr.setUnion(true);
                compiledInputs[i].plan.addAsLeaf(lr);
            }

            // Mark the start of a new TezOperator, connecting the inputs. Note
            // the parallelism is currently fixed to 1 for all TezOperators.
            blocking();

            // Then add a POPackage to the start of the new tezOp.
            POPackage pkg = getPackage(compiledInputs.length, DataType.TUPLE);
            pkg.setAlias(op.getAlias());
            curTezOp.markUnion();
            curTezOp.plan.add(pkg);
            // TODO: Union should use OnFileUnorderedKVOutput instead of
            // OnFileSortedOutput. Currently, it's not supported by Tez.
            // (TEZ-661)
        } catch (Exception e) {
            int errCode = 2034;
            String msg = "Error compiling operator " + op.getClass().getSimpleName();
            throw new TezCompilerException(msg, errCode, PigException.BUG, e);
        }
    }



    /**
     * Returns a POPackage with default packager. This method shouldn't be used
     * if special packager such as LitePackager and CombinerPackager is needed.
     */
    private POPackage getPackage(int numOfInputs, byte keyType) {
        // The default value of boolean is false
        boolean[] inner = new boolean[numOfInputs];
        POPackage pkg = new POPackage(new OperatorKey(scope, nig.getNextNodeId(scope)));
        pkg.getPkgr().setInner(inner);
        pkg.getPkgr().setKeyType(keyType);
        pkg.setNumInps(numOfInputs);
        return pkg;
    }

    private TezOperator getTezOp() {
        return new TezOperator(new OperatorKey(scope, nig.getNextNodeId(scope)));
    }
}
