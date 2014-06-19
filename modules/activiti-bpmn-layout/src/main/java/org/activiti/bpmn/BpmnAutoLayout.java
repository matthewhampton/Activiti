/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.bpmn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.swing.SwingConstants;

import org.activiti.bpmn.model.BoundaryEvent;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.CallActivity;
import org.activiti.bpmn.model.Event;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.FlowElementsContainer;
import org.activiti.bpmn.model.Gateway;
import org.activiti.bpmn.model.GraphicInfo;
import org.activiti.bpmn.model.Lane;
import org.activiti.bpmn.model.Process;
import org.activiti.bpmn.model.SequenceFlow;
import org.activiti.bpmn.model.StartEvent;
import org.activiti.bpmn.model.SubProcess;
import org.activiti.bpmn.model.Task;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGeometry;
import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxEdgeStyle;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxGraphView;

/**
 * Auto layouts a {@link BpmnModel}.
 * 
 * @author Joram Barrez
 */
public class BpmnAutoLayout {
  
  
  protected BpmnModel bpmnModel;
  
  protected int eventSize = 30;
  protected int gatewaySize = 40;
  protected int taskWidth = 100;
  protected int taskHeight = 60;
  protected int subProcessMargin = 20;
  
  protected mxGraph graph;
  private Object cellParent;
  protected Map<String, SequenceFlow> sequenceFlows;
  protected List<BoundaryEvent> boundaryEvents;
  protected Map<String, FlowElement> handledFlowElements;
  protected Map<String, Object> generatedVertices;
  protected Map<String, Object> generatedEdges;
  protected Map<String, mxCell> elementParent;
  protected Set<Object> fakeEdges;
  protected int direction; 
  protected boolean lanesAsGroups;
  
  public BpmnAutoLayout(BpmnModel bpmnModel) {
    this.bpmnModel = bpmnModel;
  }
  
  public void execute() {
    // Reset any previous DI information
    bpmnModel.getLocationMap().clear();
    bpmnModel.getFlowLocationMap().clear();
    
    // Generate DI for each process
    for (Process process : bpmnModel.getProcesses()) {
      layout(process);
    }
  }

  protected void layout(FlowElementsContainer flowElementsContainer) {
	direction = SwingConstants.NORTH;
	lanesAsGroups = false;
	
    graph = new mxGraph();
    cellParent = graph.getDefaultParent();
    graph.getModel().beginUpdate();
    
    List<mxCell> laneCells = new ArrayList<mxCell>();
    elementParent = new HashMap<String, mxCell>();
    if (lanesAsGroups)
    {
	    if (flowElementsContainer instanceof Process)
	    {
	    	Process process = (Process)flowElementsContainer;
	    	int i = 0;
	    	for (Lane lane : process.getLanes()) 
	    	{
	    		mxCell swimLane = (mxCell)graph.insertVertex(
	    				cellParent, null, ++i, 0, 0, 0, 0, 
	    				"shape=swimlane;fontSize=9;fontStyle=1;startSize=20;horizontal=false;autosize=1;");
	    		laneCells.add(swimLane);
	    		for (String elementId : lane.getFlowReferences())
	    		{
	    			elementParent.put(elementId, swimLane);
	    		}
	    	}
	    }
    }
    
    handledFlowElements = new HashMap<String, FlowElement>();
    generatedVertices = new HashMap<String, Object>();
    generatedEdges = new HashMap<String, Object>();
    fakeEdges = new HashSet<Object>();
    Set<Object> fakeVertices = new HashSet<Object>();
    
    sequenceFlows = new HashMap<String, SequenceFlow>(); // Sequence flow are gathered and processed afterwards, because we must be sure we alreadt found source and target
    boundaryEvents = new ArrayList<BoundaryEvent>(); // Boundary events are gathered and processed afterwards, because we must be sure we have its parent
    List<Object> startEvents = new ArrayList<Object>(); 
    
    // Process all elements
    for (FlowElement flowElement : flowElementsContainer.getFlowElements()) {
      
      if (flowElement instanceof SequenceFlow) {
        handleSequenceFlow((SequenceFlow) flowElement);
      } else if (flowElement instanceof Event) {
        handleEvent(flowElement);
        if (flowElement instanceof StartEvent)
        {
        	startEvents.add(generatedVertices.get(flowElement.getId()));
        }
      } else if (flowElement instanceof Gateway) {
        createGatewayVertex(flowElement);
      } else if (flowElement instanceof Task || flowElement instanceof CallActivity) {
        handleActivity(flowElement);
      } else if (flowElement instanceof SubProcess) {
        handleSubProcess(flowElement);
      }
      
      handledFlowElements.put(flowElement.getId(), flowElement);
    }
    
    // Process gathered elements
    handleBoundaryEvents();
    handleSequenceFlow();
    
    // All elements are now put in the graph. Let's layout them!
    CustomLayout layout = new CustomLayout(graph, direction);
    layout.setIntraCellSpacing(100.0);
    layout.setResizeParent(true);
    layout.setFineTuning(true);
    layout.setParentBorder(20);
    layout.setMoveParent(true);
    layout.setDisableEdgeStyle(false);
    layout.setUseBoundingBox(true);
    
    for (mxCell swimLaneCell : laneCells)
    {
    	boolean done = false;
    	while (!done)
    	{
    		layout.execute(swimLaneCell);
    		if (layout.getRoots().size() > 1)
    		{
    			List<Object> roots = new ArrayList<Object>(layout.getRoots());
    			Object v = graph.insertVertex(swimLaneCell, null, null, 0, 0, 1, 1);
    			fakeVertices.add(v);
    			for (Object r: roots	)
    			{
	    			fakeEdges.add(graph.insertEdge(
	    					swimLaneCell, 
	    					null, null, 
	    					v, r));
    			}
    		}
    		else
    		{
    			done = true;
    		}
    	}
    	
    	
    }
    if (laneCells.size() > 0)
    {
    	layout.execute(graph.getDefaultParent(), Arrays.asList(laneCells.toArray()));
    }
    else
    {
    	layout.execute(graph.getDefaultParent());
    }
    
	graph.removeCells(fakeEdges.toArray());
	graph.removeCells(fakeVertices.toArray());
	
    graph.getModel().endUpdate();
    
    generateDiagramInterchangeElements();
  }

  // BPMN element handling
  
  protected void ensureSequenceFlowIdSet(SequenceFlow sequenceFlow) {
    // We really must have ids for sequence flow to be able to generate stuff
    if (sequenceFlow.getId() == null) {
      sequenceFlow.setId("sequenceFlow-" + UUID.randomUUID().toString());
    }
  }

  protected void handleSequenceFlow(SequenceFlow sequenceFlow) {
    ensureSequenceFlowIdSet(sequenceFlow);
    sequenceFlows.put(sequenceFlow.getId(), sequenceFlow);
  }
  
  protected void handleEvent(FlowElement flowElement) {
    // Boundary events are an exception to the general way of drawing an event
    if (flowElement instanceof BoundaryEvent) {
      boundaryEvents.add((BoundaryEvent) flowElement);
    } else {
      createEventVertex(flowElement);
    }
  }
  
  protected void handleActivity(FlowElement flowElement) {
    Object activityVertex = graph.insertVertex(getCellParent(flowElement), flowElement.getId(), "", 0, 0, taskWidth, taskHeight, "");
    generatedVertices.put(flowElement.getId(), activityVertex);
  }
  
  protected void handleSubProcess(FlowElement flowElement) {
    BpmnAutoLayout bpmnAutoLayout = new BpmnAutoLayout(bpmnModel);
    bpmnAutoLayout.layout((SubProcess) flowElement);
    
    double subProcessWidth = bpmnAutoLayout.getGraph().getView().getGraphBounds().getWidth();
    double subProcessHeight = bpmnAutoLayout.getGraph().getView().getGraphBounds().getHeight();
    Object subProcessVertex = graph.insertVertex(getCellParent(flowElement), flowElement.getId(), "", 0, 0, 
            subProcessWidth + 2 * subProcessMargin, subProcessHeight + 2 * subProcessMargin);
    generatedVertices.put(flowElement.getId(), subProcessVertex);
  }
  
  protected void handleBoundaryEvents() {
    for (BoundaryEvent boundaryEvent : boundaryEvents) {
      mxGeometry geometry;
	  if (direction == SwingConstants.NORTH)
	  {
		  geometry = new mxGeometry(1.0, 1.0, eventSize, eventSize);
		  geometry.setOffset(new mxPoint(-(eventSize/3), -(eventSize/3)));		  
	  }
	  else
	  {
		  geometry = new mxGeometry(0.8, 1.0, eventSize, eventSize);
		  geometry.setOffset(new mxPoint(-(eventSize/2), -(eventSize/2)));		  
	  }
      geometry.setRelative(true);
      mxCell boundaryPort = new mxCell(null, geometry, "shape=ellipse;perimter=ellipsePerimeter");
      boundaryPort.setId("boundary-event-" + boundaryEvent.getId());
      boundaryPort.setVertex(true);

      Object portParent = null;
      if (boundaryEvent.getAttachedToRefId() != null) {
        portParent = generatedVertices.get(boundaryEvent.getAttachedToRefId());
      } else if (boundaryEvent.getAttachedToRef() != null) {
        portParent = generatedVertices.get(boundaryEvent.getAttachedToRef().getId());
      } else {
        throw new RuntimeException("Could not generate DI: boundaryEvent '" + boundaryEvent.getId() + "' has no attachedToRef");
      }
      if (portParent == null)
      {
    	  throw new RuntimeException("Could not generate DI: boundaryEvent '" + boundaryEvent.getId() + "' has no matching attachedToRef");
      }
      graph.addCell(boundaryPort, portParent);
      generatedVertices.put(boundaryEvent.getId(), boundaryPort);
    }
  }
  
  protected void handleSequenceFlow() {
        
    for (SequenceFlow sequenceFlow : sequenceFlows.values()) {
      mxCell sourceVertex = (mxCell)generatedVertices.get(sequenceFlow.getSourceRef());
      mxCell targertVertex = (mxCell)generatedVertices.get(sequenceFlow.getTargetRef());
      
      String style = null;
       
      if (handledFlowElements.get(sequenceFlow.getSourceRef()) instanceof BoundaryEvent) 
      {
    	  BoundaryEvent e = (BoundaryEvent)handledFlowElements.get(sequenceFlow.getSourceRef());
    	  
    	  // Sequence flow out of boundary events are handled in a different way,
		  // to make them visually appealing for the eye of the dear end user.
		  style = "edgeStyle=orthogonalEdgeStyle;";
		  style += direction == SwingConstants.NORTH ? "" : "exitX=0.5;exitY=1.0;entryX=0.5;entryY=1.0;";
		  //Insert a fake edge from the parent, so that the tree is held together:
		  fakeEdges.add(graph.insertEdge(getCellParent(sequenceFlow), sequenceFlow.getId(), "", generatedVertices.get(e.getAttachedToRefId()), targertVertex, style));
      }
      else
      {
		  int fromLaneNr = (sourceVertex.getParent().getValue() instanceof Integer) ? (Integer)sourceVertex.getParent().getValue() : 0;
		  int toLaneNr = (targertVertex.getParent().getValue() instanceof Integer) ? (Integer)targertVertex.getParent().getValue() : 0;
		  if (fromLaneNr == toLaneNr)
		  {
			  style = direction == SwingConstants.NORTH ? "edgeStyle=orthogonalEdgeStyle;" : "orthogonal=true;edgeStyle=elbowEdgeStyle";
			  style += direction == SwingConstants.NORTH ? "" : ";entryX=0;entryY=0.5;";
		  }
		  else if (fromLaneNr < toLaneNr)
		  {
			  style = "edgeStyle=segmentEdgeStyle";
			  //style += ";entryX=0.3;entryY=0;exitX=0.7;exitY=1;";
			  style += "entryY=0;exitY=1;";
		  }
		  else
		  {
			  style = "edgeStyle=segmentEdgeStyle";
			  //style += ";entryX=0.3;entryY=1.0;exitX=0.7;exitY=0";
			  style += ";entryY=1.0;exitY=0";
		  }
		  
      }
      
      Object sequenceFlowEdge = graph.insertEdge(getCellParent(sequenceFlow), sequenceFlow.getId(), "", sourceVertex, targertVertex, style);
    		  
      generatedEdges.put(sequenceFlow.getId(), sequenceFlowEdge);
    }
  }
  
  // Graph cell creation
  
  protected void createEventVertex(FlowElement flowElement) {
    // Add styling for events if needed
    // Add vertex representing event to graph
    Object eventVertex = graph.insertVertex(getCellParent(flowElement), flowElement.getId(), "", 0, 0, eventSize, eventSize, "shape=ellipse;perimeter=ellipsePerimeter");
    generatedVertices.put(flowElement.getId(), eventVertex);
  }
  
  protected void createGatewayVertex(FlowElement flowElement) {
    // Create gateway node 
    Object gatewayVertex = graph.insertVertex(getCellParent(flowElement), flowElement.getId(), "", 0, 0, gatewaySize, gatewaySize, "shape=rhombus;perimeter=rhombusPerimeter");
    generatedVertices.put(flowElement.getId(), gatewayVertex);
  }
  
  // Diagram interchange generation
  
  protected void generateDiagramInterchangeElements() {
    generateActivityDiagramInterchangeElements();
    generateSequenceFlowDiagramInterchangeElements();
  }
  
  protected void generateActivityDiagramInterchangeElements() {
    for (String flowElementId : generatedVertices.keySet()) {
      Object vertex = generatedVertices.get(flowElementId);
      mxCellState cellState = graph.getView().getState(vertex);
      GraphicInfo subProcessGraphicInfo = createDiagramInterchangeInformation(handledFlowElements.get(flowElementId), 
              (int) cellState.getX(), (int) cellState.getY(), (int) cellState.getWidth(), (int) cellState.getHeight());
      
      // The DI for the elements of a subprocess are generated without knowledge of the rest of the graph
      // So we must translate all it's elements with the x and y of the subprocess itself
      if (handledFlowElements.get(flowElementId) instanceof SubProcess) {
        SubProcess subProcess =(SubProcess) handledFlowElements.get(flowElementId);
        
        // Always expanded when auto layouting
        subProcessGraphicInfo.setExpanded(true);
        
        // Translate
        double subProcessX = cellState.getX();
        double subProcessY = cellState.getY();
        double translationX = subProcessX + subProcessMargin;
        double translationY = subProcessY + subProcessMargin;
        for (FlowElement subProcessElement : subProcess.getFlowElements()) {
          if (subProcessElement instanceof SequenceFlow) {
            List<GraphicInfo> graphicInfoList = bpmnModel.getFlowLocationGraphicInfo(subProcessElement.getId());
            for (GraphicInfo graphicInfo : graphicInfoList) {
              graphicInfo.setX(graphicInfo.getX() + translationX);
              graphicInfo.setY(graphicInfo.getY() + translationY);
            }
          } else {
            GraphicInfo graphicInfo = bpmnModel.getLocationMap().get(subProcessElement.getId());
            graphicInfo.setX(graphicInfo.getX() + translationX);
            graphicInfo.setY(graphicInfo.getY() + translationY);
          }
        }
      }
    }
  }

  protected void generateSequenceFlowDiagramInterchangeElements() {
    for (String sequenceFlowId : generatedEdges.keySet()) {
      Object edge = generatedEdges.get(sequenceFlowId);
      List<mxPoint> points = graph.getView().getState(edge).getAbsolutePoints();
      
      // JGraphX has this funny way of generating the outgoing sequence flow of a gateway
      // Visually, we'd like them to originate from one of the corners of the rhombus,
      // hence we force the starting point of the sequence flow to the closest rhombus corner point.
      FlowElement sourceElement = handledFlowElements.get(sequenceFlows.get(sequenceFlowId).getSourceRef()); 
      if (direction != SwingConstants.NORTH && sourceElement instanceof Gateway && ((Gateway) sourceElement).getOutgoingFlows().size() > 1) {
        mxPoint startPoint = points.get(0);
        Object gatewayVertex = generatedVertices.get(sourceElement.getId());
        mxCellState gatewayState = graph.getView().getState(gatewayVertex);
        
        mxPoint northPoint = new mxPoint(gatewayState.getX() + (gatewayState.getWidth()) / 2, gatewayState.getY());
        mxPoint southPoint = new mxPoint(gatewayState.getX() + (gatewayState.getWidth()) / 2, gatewayState.getY() + gatewayState.getHeight());
        mxPoint eastPoint = new mxPoint(gatewayState.getX() + gatewayState.getWidth(), gatewayState.getY() + (gatewayState.getHeight()) / 2);
        mxPoint westPoint = new mxPoint(gatewayState.getX(), gatewayState.getY() + (gatewayState.getHeight()) / 2);
        
        double closestDistance = Double.MAX_VALUE;
        mxPoint closestPoint = null;
        for (mxPoint rhombusPoint : Arrays.asList(northPoint, southPoint, eastPoint, westPoint)) {
          double distance = euclidianDistance(startPoint, rhombusPoint);
          if (distance < closestDistance) {
            closestDistance = distance;
            closestPoint = rhombusPoint;
          }
        }
        startPoint.setX(closestPoint.getX());
        startPoint.setY(closestPoint.getY());
        
        // We also need to move the second point.
        // Since we know the layout is from left to right, this is not a problem
        if (points.size() > 1) {
          mxPoint nextPoint = points.get(1);
          nextPoint.setY(closestPoint.getY());
        }
        
      }
      
      createDiagramInterchangeInformation((SequenceFlow) handledFlowElements.get(sequenceFlowId), optimizeEdgePoints(points));
    }
  }

  protected double euclidianDistance(mxPoint point1, mxPoint point2) {
    return Math.sqrt( ( (point2.getX() - point1.getX())*(point2.getX() - point1.getX()) 
            + (point2.getY() - point1.getY())*(point2.getY() - point1.getY()) ) );
  }
  
  // JGraphX sometime generates points that visually are not really necessary.
  // This method will remove any such points.
  protected List<mxPoint> optimizeEdgePoints(List<mxPoint> unoptimizedPointsList) {
    List<mxPoint> optimizedPointsList = new ArrayList<mxPoint>();
    for (int i=0; i<unoptimizedPointsList.size(); i++) {

      boolean keepPoint = true;
      mxPoint currentPoint = unoptimizedPointsList.get(i);
      
      // When three points are on the same x-axis with same y value, the middle point can be removed
      if (i > 0 && i != unoptimizedPointsList.size() - 1) {
        
        mxPoint previousPoint = unoptimizedPointsList.get(i - 1);
        mxPoint nextPoint = unoptimizedPointsList.get(i + 1);
        
        if (currentPoint.getX() >= previousPoint.getX() 
                && currentPoint.getX() <= nextPoint.getX()
                && currentPoint.getY() == previousPoint.getY()
                && currentPoint.getY() == nextPoint.getY()) {
          keepPoint = false;
        } else if (currentPoint.getY() >= previousPoint.getY()
                && currentPoint.getY() <= nextPoint.getY()
                && currentPoint.getX() == previousPoint.getX()
                && currentPoint.getX() == nextPoint.getX()) {
          keepPoint = false;
        }
        
      }
      
      if (keepPoint) {
        optimizedPointsList.add(currentPoint);
      }
      
    }
    
    return optimizedPointsList;
  }
  
  protected GraphicInfo createDiagramInterchangeInformation(FlowElement flowElement, int x, int y, int width, int height) {
    GraphicInfo graphicInfo = new GraphicInfo();
    graphicInfo.setX(x);
    graphicInfo.setY(y);
    graphicInfo.setWidth(width);
    graphicInfo.setHeight(height);
    graphicInfo.setElement(flowElement);
    bpmnModel.addGraphicInfo(flowElement.getId(), graphicInfo);
    
    return graphicInfo;
  }
  
  protected void createDiagramInterchangeInformation(SequenceFlow sequenceFlow, List<mxPoint> waypoints) {
    List<GraphicInfo> graphicInfoForWaypoints = new ArrayList<GraphicInfo>();
    for (mxPoint waypoint : waypoints) {
      GraphicInfo graphicInfo = new GraphicInfo();
      graphicInfo.setElement(sequenceFlow);
      graphicInfo.setX(waypoint.getX());
      graphicInfo.setY(waypoint.getY());
      graphicInfoForWaypoints.add(graphicInfo);
    }
    bpmnModel.addFlowGraphicInfoList(sequenceFlow.getId(), graphicInfoForWaypoints);
  }
  
  // Getters and Setters
  
  
  public mxGraph getGraph() {
    return graph;
  }

  public void setGraph(mxGraph graph) {
    this.graph = graph;
  }
  
  public int getEventSize() {
    return eventSize;
  }

  public void setEventSize(int eventSize) {
    this.eventSize = eventSize;
  }
  
  public int getGatewaySize() {
    return gatewaySize;
  }
  
  public void setGatewaySize(int gatewaySize) {
    this.gatewaySize = gatewaySize;
  }

  
  public int getTaskWidth() {
    return taskWidth;
  }

  public void setTaskWidth(int taskWidth) {
    this.taskWidth = taskWidth;
  }

  public int getTaskHeight() {
    return taskHeight;
  }
  
  public void setTaskHeight(int taskHeight) {
    this.taskHeight = taskHeight;
  } 
  
  public int getSubProcessMargin() {
    return subProcessMargin;
  }
  
  public void setSubProcessMargin(int subProcessMargin) {
    this.subProcessMargin = subProcessMargin;
  }

  protected Object getCellParent(FlowElement flowElement) {
	Object p = elementParent.get(flowElement.getId());
	if (p == null)
	{
		return cellParent;
	}
	return p;
  }

  public Object getVertex(String id)
  {
	  return this.generatedVertices.get(id);
  }

// Due to a bug (see http://forum.jgraph.com/questions/5952/mxhierarchicallayout-not-correct-when-using-child-vertex)
  // We must extend the default hierarchical layout to tweak it a bit (see url link) otherwise the layouting crashes.
  //
  // Verify again with a later release if fixed (ie the mxHierarchicalLayout can be used directly)
  static class CustomLayout extends mxHierarchicalLayout {
    
    public CustomLayout(mxGraph graph, int orientation) {
      super(graph, orientation);
      this.traverseAncestors = false;
    }
    
    public List<Object> getRoots()
    {
    	return roots;
    }
    
  }
  
}
