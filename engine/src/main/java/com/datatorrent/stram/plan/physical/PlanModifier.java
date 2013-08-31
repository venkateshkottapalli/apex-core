package com.datatorrent.stram.plan.physical;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.validation.ValidationException;

import com.datatorrent.stram.DAGPropertiesBuilder;
import com.datatorrent.stram.PhysicalPlan;
import com.datatorrent.stram.PhysicalPlan.PTContainer;
import com.datatorrent.stram.PhysicalPlan.PTOperator;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.logical.Operators;
import com.datatorrent.stram.plan.logical.LogicalPlan.InputPortMeta;
import com.datatorrent.stram.plan.logical.LogicalPlan.OperatorMeta;
import com.datatorrent.stram.plan.logical.LogicalPlan.StreamMeta;
import com.google.common.collect.Sets;
import com.datatorrent.api.Operator;
import com.datatorrent.api.Operator.InputPort;
import com.datatorrent.api.Operator.OutputPort;

/**
 * Modification of the query plan on running application. Will first apply
 * logical plan changes to a copy of the logical plan and then apply changes to
 * the original and physical plan after validation passes.
 *
 * @since 0.3.2
 */
public class PlanModifier {

  final private LogicalPlan logicalPlan;
  final private PhysicalPlan physicalPlan;

  private final Set<PTOperator> affectedOperators = Sets.newHashSet();
  private final Set<PTOperator> newOperators = Sets.newHashSet();
  private final Set<PTOperator> removedOperators = Sets.newHashSet();

  /**
   * For dry run on logical plan only
   * @param logicalPlan
   */
  public PlanModifier(LogicalPlan logicalPlan)
  {
    this.logicalPlan = logicalPlan;
    this.physicalPlan = null;
  }

  /**
   * For modification of physical plan
   * @param plan
   */
  public PlanModifier(PhysicalPlan plan)
  {
    this.physicalPlan = plan;
    this.logicalPlan = plan.getDAG();
  }

  public StreamMeta addSinks(String id, Operator.InputPort<?>... sinks)
  {
    StreamMeta sm = logicalPlan.getStream(id);
    if (sm == null) {
      throw new AssertionError("Stream " + id + " is not found!");
    }
    for (Operator.InputPort<?> sink : sinks) {
      sm.addSink(sink);
      if (physicalPlan != null) {
        for (InputPortMeta ipm : sm.getSinks()) {
          if (ipm.getPortObject() == sink) {
            physicalPlan.connectInput(ipm, affectedOperators);
          }
        }
      }
    }
    return sm;
  }

  public <T> StreamMeta addStream(String id, Operator.OutputPort<? extends T> source, Operator.InputPort<?>... sinks)
  {
    StreamMeta sm = logicalPlan.getStream(id);
    if (sm != null) {
      if (sm.getSource().getOperatorWrapper().getMeta(source) != sm.getSource()) {
        throw new AssertionError(String.format("Stream %s already connected to %s", sm, sm.getSource()));
      }
    } else {
      // fails on duplicate stream name
      @SuppressWarnings("unchecked")
      StreamMeta newStream = logicalPlan.addStream(id, source);
      sm = newStream;
    }
    return addSinks(id, sinks);
  }

  /**
   * Add stream to logical plan. If a stream with same name and source already
   * exists, the new downstream operator will be attached to it.
   *
   * @param streamName
   * @param sourceOperName
   * @param sourcePortName
   * @param targetOperName
   * @param targetPortName
   */
  public void addStream(String streamName, String sourceOperName, String sourcePortName, String targetOperName, String targetPortName)
  {
    OperatorMeta om = logicalPlan.getOperatorMeta(sourceOperName);
    if (om == null) {
      throw new ValidationException("Invalid operator name " + sourceOperName);
    }

    Operators.PortMappingDescriptor portMap = new Operators.PortMappingDescriptor();
    Operators.describe(om.getOperator(), portMap);

    OutputPort<?> sourcePort = portMap.outputPorts.get(sourcePortName);
    if (sourcePort == null) {
      throw new AssertionError(String.format("Invalid port %s (%s)", sourcePortName, om));
    }
    addStream(streamName, sourcePort, getInputPort(targetOperName, targetPortName));
  }

  /**
   * Add sink to an existing stream to logical plan.
   *
   * @param streamName
   * @param targetOperName
   * @param targetPortName
   */
  public void addSink(String streamName, String targetOperName, String targetPortName)
  {
    addSinks(streamName, getInputPort(targetOperName, targetPortName));
  }

  private OperatorMeta assertGetOperator(String operName)
  {
    OperatorMeta om = logicalPlan.getOperatorMeta(operName);
    if (om == null) {
      throw new AssertionError("Invalid operator name " + operName);
    }
    return om;
  }

  private InputPort<?> getInputPort(String operName, String portName)
  {
    OperatorMeta om = assertGetOperator(operName);
    Operators.PortMappingDescriptor portMap = new Operators.PortMappingDescriptor();
    Operators.describe(om.getOperator(), portMap);

    InputPort<?> port = portMap.inputPorts.get(portName);
    if (port == null) {
      throw new AssertionError(String.format("Invalid port %s (%s)", portName, om));
    }
    return port;
  }

  /**
   * Remove the named stream. Ignored when stream does not exist.
   * @param streamName
   */
  public void removeStream(String streamName)
  {
    StreamMeta sm = logicalPlan.getStream(streamName);
    if (sm == null) {
      return;
    }

    if (physicalPlan != null) {
      // associated operators will redeploy
      physicalPlan.removeLogicalStream(sm, affectedOperators);
    }
    // remove from logical plan
    sm.remove();
  }

  /**
   * Add operator to logical plan.
   * @param name
   */
  public void addOperator(String name, Operator operator)
  {
    logicalPlan.addOperator(name, operator);
    // add to physical plan after all changes are done
    if (physicalPlan != null) {
      OperatorMeta om = logicalPlan.getMeta(operator);
      physicalPlan.addLogicalOperator(om);
      // keep track of new instances
      newOperators.addAll(physicalPlan.getOperators(om));
    }
  }

  /**
   * Remove named operator and any outgoing streams.
   * @param name
   */
  public void removeOperator(String name)
  {
    OperatorMeta om = logicalPlan.getOperatorMeta(name);
    if (om == null) {
      return;
    }

    if (!om.getInputStreams().isEmpty()) {
      for (Map.Entry<InputPortMeta, StreamMeta> input : om.getInputStreams().entrySet()) {
        if (input.getValue().getSinks().size() == 1) {
          // would result in dangling stream
          String msg = String.format("Operator %s connected to input streams %s", om.getName(), om.getInputStreams());
          throw new ValidationException(msg);
        }
      }
    }
    if (!om.getOutputStreams().isEmpty()) {
      String msg = String.format("Operator %s connected to output streams %s", om.getName(), om.getOutputStreams());
      throw new ValidationException(msg);
    }
    /*
    // remove associated sinks
    Map<InputPortMeta, StreamMeta> inputStreams = om.getInputStreams();
    for (Map.Entry<InputPortMeta, StreamMeta> e : inputStreams.entrySet()) {
      if (e.getKey().getOperatorWrapper() == om) {
        if (e.getValue().getSinks().size() == 1) {
          // drop stream
          e.getValue().remove();
        }
      }
    }
    */
    logicalPlan.removeOperator(om.getOperator());

    if (physicalPlan != null) {
      physicalPlan.removeLogicalOperator(om, removedOperators);
    }
  }

  /**
   * Set the property on a new operator. Since this is only intended to modify
   * previously added operators, no change to the physical plan is required.
   *
   * @param operatorName
   * @param propertyName
   * @param propertyValue
   */
  public void setOperatorProperty(String operatorName, String propertyName, String propertyValue)
  {
    OperatorMeta om = assertGetOperator(operatorName);
    if (physicalPlan != null)
    {
      for (PTOperator oper : physicalPlan.getOperators(om)) {
        if (!newOperators.contains(oper)) {
          throw new ValidationException("Properties can only be set on new operators: " + om + " " + propertyName + " " + propertyValue);
        }
      }
    }
    Map<String, String> props = Collections.singletonMap(propertyName, propertyValue);
    DAGPropertiesBuilder.setOperatorProperties(om.getOperator(), props);
  }

  /**
   * Deploy plan changes to execution layer..
   */
  public void applyChanges(PhysicalPlan.PlanContext physicalPlanContext)
  {
    // assign containers
    Set<PTContainer> newContainers = Sets.newHashSet();
    Set<PTContainer> releaseContainers = Sets.newHashSet();
    physicalPlan.assignContainers(newOperators, newContainers, releaseContainers);

    for (PTOperator newOperator : newOperators) {
      physicalPlan.initCheckpoint(newOperator);
    }

    // existing downstream operators require redeploy
    Set<PTOperator> undeployOperators = physicalPlan.getDependents(newOperators);
    undeployOperators.removeAll(newOperators);

    Set<PTOperator> deployOperators = physicalPlan.getDependents(newOperators);

    Set<PTOperator> redeployOperators = physicalPlan.getDependents(this.affectedOperators);
    undeployOperators.addAll(redeployOperators);
    undeployOperators.addAll(removedOperators);
    undeployOperators.removeAll(newOperators);

    deployOperators.addAll(redeployOperators);
    deployOperators.removeAll(removedOperators);

    physicalPlanContext.deploy(releaseContainers, undeployOperators, newContainers, deployOperators);
  }

}
