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
import java.util.Collections;
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
import org.activiti.bpmn.model.UserTask;
import org.apache.commons.lang3.text.WordUtils;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.hierarchical.model.mxGraphAbstractHierarchyCell;
import com.mxgraph.layout.hierarchical.model.mxGraphHierarchyEdge;
import com.mxgraph.layout.hierarchical.model.mxGraphHierarchyNode;
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
  protected Map<String, Lane> elementLane;
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
    elementLane = new HashMap<String, Lane>();
    if (flowElementsContainer instanceof Process)
    {
    	Process process = (Process)flowElementsContainer;
    	int i = 0;
    	for (Lane lane : process.getLanes()) 
    	{
    		mxCell swimLane = null;
    	    if (lanesAsGroups)
    	    {
	    		swimLane = (mxCell)graph.insertVertex(
	    				cellParent, null, ++i, 0, 0, 0, 0, 
	    				"shape=swimlane;fontSize=9;fontStyle=1;startSize=20;horizontal=false;autosize=1;");
	    		laneCells.add(swimLane);
    	    }
    		for (String elementId : lane.getFlowReferences())
    		{
    		    if (lanesAsGroups)
    		    {
    		    	elementParent.put(elementId, swimLane);
    		    }
    		    elementLane.put(elementId, lane);
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
    
    if (lanesAsGroups)
    {
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
    String wrapped = WordUtils.wrap(flowElement.getName(), 22, System.getProperty("line.separator"), true);
    int lines = wrapped.split(System.getProperty("line.separator")).length;
    String topbottom = (lines > 1 ? "10" : "20");
    String leftright = "20";
    Object activityVertex = graph.insertVertex(getCellParent(flowElement), flowElement.getId(), 
    		wrapped, 0, 0, taskWidth, taskHeight, String.format("spacingLeft=%s;spacingRight=%s;spacingTop=%s;spacingBottom=%s", leftright, leftright, topbottom, topbottom) );
    graph.updateCellSize(activityVertex);
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
  }
  
  protected void handleSequenceFlow() {
        
    for (SequenceFlow sequenceFlow : sequenceFlows.values()) {
      mxCell sourceVertex;
      mxCell targertVertex = (mxCell)generatedVertices.get(sequenceFlow.getTargetRef());
      String style;
       
      if (handledFlowElements.get(sequenceFlow.getSourceRef()) instanceof BoundaryEvent) 
      {
    	  BoundaryEvent boundaryEvent = (BoundaryEvent)handledFlowElements.get(sequenceFlow.getSourceRef());
          Object portParent = null;
          if (boundaryEvent.getAttachedToRefId() != null) {
        	  sourceVertex = (mxCell)generatedVertices.get(boundaryEvent.getAttachedToRefId());
          } else if (boundaryEvent.getAttachedToRef() != null) {
        	  sourceVertex = (mxCell)generatedVertices.get(boundaryEvent.getAttachedToRef().getId());
          } else {
            throw new RuntimeException("Could not generate DI: boundaryEvent '" + boundaryEvent.getId() + "' has no attachedToRef");
          }
    	  
    	  // Sequence flow out of boundary events are handled in a different way,
		  // to make them visually appealing for the eye of the dear end user.
		  style = "edgeStyle=orthogonalEdgeStyle;";
		  style += direction == SwingConstants.NORTH ? "" : "exitX=0.5;exitY=1.0;entryX=0.5;entryY=1.0;";
      }
      else
      {
    	  sourceVertex = (mxCell)generatedVertices.get(sequenceFlow.getSourceRef());
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

  private double distanceSquared(mxPoint a, mxPoint b)
  {
	  double dx = a.getX()-b.getX();
	  double dy = a.getY()-b.getY();
	  
	  return dx*dx + dy*dy;
  }
  
  protected List<mxPoint> getCircleLineIntersectionPoint(mxPoint pointA,
          mxPoint pointB, mxPoint center, double radius) {
      double baX = pointB.getX() - pointA.getX();
      double baY = pointB.getY() - pointA.getY();
      double caX = center.getX() - pointA.getX();
      double caY = center.getY() - pointA.getY();

      double a = baX * baX + baY * baY;
      double bBy2 = baX * caX + baY * caY;
      double c = caX * caX + caY * caY - radius * radius;

      double pBy2 = bBy2 / a;
      double q = c / a;

      double disc = pBy2 * pBy2 - q;
      if (disc < 0) {
          return Collections.emptyList();
      }
      // if disc == 0 ... dealt with later
      double tmpSqrt = Math.sqrt(disc);
      double abScalingFactor1 = -pBy2 + tmpSqrt;
      double abScalingFactor2 = -pBy2 - tmpSqrt;

      mxPoint p1 = new mxPoint(pointA.getX() - baX * abScalingFactor1, pointA.getY()
              - baY * abScalingFactor1);
      if (disc == 0) { // abScalingFactor1 == abScalingFactor2
          return Collections.singletonList(p1);
      }
      mxPoint p2 = new mxPoint(pointA.getX() - baX * abScalingFactor2, pointA.getY()
              - baY * abScalingFactor2);
      return Arrays.asList(p1, p2);
  }
  
  protected boolean isOnLineSegment(mxPoint p, mxPoint a, mxPoint b)
  {
	  return isBetween(p.getX(), a.getX(), b.getX()) && isBetween(p.getY(), a.getY(), b.getY());
  }
  
  protected boolean isBetween(double v, double va, double vb)
  {
	  if (Math.abs(va-vb)<0.01)
	  {
		  return Math.abs(va-v)<0.01;
	  }
	  else
	  {
		  return v <= Math.max(va,  vb) && v >= Math.min(va,  vb);
	  }
  }
  
  protected void generateSequenceFlowDiagramInterchangeElements() {
    for (String sequenceFlowId : generatedEdges.keySet()) {
      Object edge = generatedEdges.get(sequenceFlowId);
      List<mxPoint> points = graph.getView().getState(edge).getAbsolutePoints();
      
      FlowElement sourceElement = handledFlowElements.get(sequenceFlows.get(sequenceFlowId).getSourceRef()); 
      if (sourceElement instanceof BoundaryEvent) 
      {
    	  BoundaryEvent boundaryEvent = (BoundaryEvent)sourceElement;
    	  mxPoint startPoint = points.get(0);
          //First we put the boundary event on the boundary of the activity, at the point that the edge begins:
    	  createDiagramInterchangeInformation(boundaryEvent, (int)startPoint.getX()-(eventSize/2), (int)startPoint.getY()-(eventSize/2), eventSize, eventSize);
    	  //Now we move the start of the edge to the edge of the boundary event
    	  List<mxPoint> new_points = new ArrayList<mxPoint>();
    	  int i = 0;
    	  while (i < (points.size()-1) && (distanceSquared(startPoint, points.get(i)) < eventSize*eventSize))
    	  {
    		  i++;
    	  }
    	  mxPoint last_in_circle = points.get(i-1);
    	  mxPoint first_outside_circle = points.get(i);
    	  List<mxPoint> l = getCircleLineIntersectionPoint(last_in_circle, first_outside_circle, startPoint, eventSize/2);
    	  
    	  //We're using orthogonal edges, so this line is either horizontal, or vertical:
    	  for (mxPoint p : l)
    	  {
    		  if (isOnLineSegment(p, last_in_circle, first_outside_circle))
    		  {
    			  new_points.add(p);
        		  break;
    		  }
    	  }
    	  if (new_points.size() != 1)
    	  {
    		  throw new RuntimeException("Could not find an intersection of the edge and the boundary event");
    	  }
    	  
    	  while (i < points.size())
    	  {
    		  new_points.add(points.get(i));
    		  i++;
    	  }
    	  points = new_points;
      }
      if (direction != SwingConstants.NORTH && sourceElement instanceof Gateway && ((Gateway) sourceElement).getOutgoingFlows().size() > 1) {
        // JGraphX has this funny way of generating the outgoing sequence flow of a gateway
        // Visually, we'd like them to originate from one of the corners of the rhombus,
        // hence we force the starting point of the sequence flow to the closest rhombus corner point.
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
  class CustomLayout extends mxHierarchicalLayout {
    
    public CustomLayout(mxGraph graph, int orientation) {
      super(graph, orientation);
      //this.traverseAncestors = false;
    }
    
    public List<Object> getRoots()
    {
    	return roots;
    }
    
    protected void calculateBumpedDownTargetRank(
    		mxGraphHierarchyNode node,
    		Map<mxGraphHierarchyNode, Integer> targetRank,
    		int ensureLessThan)
    {

    	if (node.temp[0] < ensureLessThan)
    	{
    		return;
    	}
    	
    	if (targetRank.containsKey(node))
    	{
    		if (targetRank.get(node) < ensureLessThan)
    		{
    			return;
    		}
    	}

    	targetRank.put(node, ensureLessThan-1);
    	

    	if (node instanceof mxGraphHierarchyNode)
    	{
    		
	    	for (mxGraphHierarchyEdge outgoingEdgeNode: ((mxGraphHierarchyNode)node).connectsAsSource)
	    	{
	    		if (outgoingEdgeNode.target.temp[0] < node.temp[0])
	    		{
	    			calculateBumpedDownTargetRank(outgoingEdgeNode.target, targetRank, ensureLessThan-1);
	    		}
	    		else
	    		{
	    			System.out.println(outgoingEdgeNode.target.temp[0]);
	    		}
	    	}
    	}
    }
    	
    protected int bumpDownRank(
		Map<Integer, Set<mxGraphHierarchyNode>> ranks, 
		int minRank,
		Map<mxGraphHierarchyNode, Integer> targetRank)
    {
    	for (Map.Entry<mxGraphHierarchyNode, Integer> e : targetRank.entrySet())
    	{
    		mxGraphHierarchyNode node = e.getKey();
    		int rank = e.getValue();
    		
//	    	System.out.println(
//	    			String.format("Bumping %s (%s) from %d to %d", 
//	    					graph.getModel().getValue(node.cell),
//	    					((mxCell)node.cell).getId(),
//	    					node.temp[0], rank));
	
	    	ranks.get(node.temp[0]).remove(node);
	    	node.temp[0] = rank;
			if (!ranks.containsKey(rank))
			{
				ranks.put(rank, new HashSet<mxGraphHierarchyNode>());
			}
			ranks.get(rank).add(node);
			minRank = Math.min(minRank, node.temp[0]);
    	}
    	
    	return minRank;
    }
    
    private Map<Integer, Set<mxGraphHierarchyNode>> getCellsByRank()
    {
    	Map<Integer, Set<mxGraphHierarchyNode>> ranks = new HashMap<Integer, Set<mxGraphHierarchyNode>>();
		for (Map.Entry<Object, mxGraphHierarchyNode> e : model.getVertexMapper().entrySet())
		{
			int rank = e.getValue().temp[0];
			if (!ranks.containsKey(rank))
			{
				ranks.put(rank, new HashSet<mxGraphHierarchyNode>());
			}
			ranks.get(rank).add(e.getValue());			
		}
		return ranks;
    }
    
    private Map<Lane, Set<mxGraphHierarchyNode>> getUserTaskLanesOfNodes(Set<mxGraphHierarchyNode> nodes)
    {
    	Map<Lane, Set<mxGraphHierarchyNode>> lanes = new HashMap<Lane, Set<mxGraphHierarchyNode>>();
    	for (mxGraphHierarchyNode node: nodes)
    	{
    		FlowElement e = BpmnAutoLayout.this.handledFlowElements.get(((mxCell)node.cell).getId());
    		if (e != null && e instanceof UserTask)
    		{
	    		Lane l = BpmnAutoLayout.this.elementLane.get(e.getId());
	    		if (l != null )
	    		{
		    		if (!lanes.containsKey(l))
		    		{
		    			lanes.put(l,new HashSet<mxGraphHierarchyNode>());
		    		}
		    		lanes.get(l).add(node);
	    		}
    		}
    	}
    	return lanes;
    }
    
    private Map<Integer, Set<mxGraphHierarchyNode>> copyRanksAndRestoreTemp(Map<Integer, Set<mxGraphHierarchyNode>> ranks)
    {
    	Map<Integer, Set<mxGraphHierarchyNode>> newRanks = new HashMap<Integer, Set<mxGraphHierarchyNode>>();
		for (Map.Entry<Integer, Set<mxGraphHierarchyNode>> e : ranks.entrySet())
		{
			newRanks.put(e.getKey(), new HashSet<mxGraphHierarchyNode>(e.getValue()));
			for (mxGraphHierarchyNode node : e.getValue())
			{
				node.temp[0] = e.getKey();
			}
		}
		return newRanks;
    }
    
    private int getRanksScore(Map<Integer, Set<mxGraphHierarchyNode>> ranks, int minRank)
    {
    	int countLaneChanges = 0;
    	
    	Lane currentLane = null;
    	for (int i=model.maxRank; i>=minRank; i--)
    	{
    		
    		Map<Lane, Set<mxGraphHierarchyNode>> laneNodeMap = getUserTaskLanesOfNodes(ranks.get(i));
    		if (laneNodeMap.size() > 1)
    		{
    			throw new RuntimeException("Ambiguous lane for rank");
    		}
    		if (laneNodeMap.size() == 1)
    		{
    			Lane nextLane = laneNodeMap.keySet().iterator().next();
    			if (currentLane != null && nextLane != currentLane)
    			{
    				countLaneChanges += 1;
    			}
				currentLane = nextLane;
    		}
    		
		}
		return -countLaneChanges;
    }
        
    private Object[] ensureOneLanePerRank(Map<Integer, Set<mxGraphHierarchyNode>> ranks, int minRank, int i)
    {
    	if (i < minRank)
    	{
    		return new Object[] {ranks, minRank};
    	}
    	Map<Lane, Set<mxGraphHierarchyNode>> laneNodeMap;
		if ((laneNodeMap = getUserTaskLanesOfNodes(ranks.get(i))).size() > 1)
		{
			Map<Integer, Set<mxGraphHierarchyNode>> bestNewRanks = null;
			int bestNewMinRank = -1;
			int bestRanksScore = -1;
			
			for (Lane lane: laneNodeMap.keySet())
			{
				Map<mxGraphHierarchyNode, Integer> targetRanks = new HashMap<mxGraphHierarchyNode, Integer>();
				for (Lane other_lane: laneNodeMap.keySet())
				{
					if (lane != other_lane)
					{
						for (mxGraphHierarchyNode node : laneNodeMap.get(other_lane))
						{
							calculateBumpedDownTargetRank(node, targetRanks, node.temp[0]);
						}
					}
				}
				Map<Integer, Set<mxGraphHierarchyNode>> newRanks = copyRanksAndRestoreTemp(ranks);
				int newMinRank = bumpDownRank(newRanks, minRank, targetRanks);
				Object[] r = ensureOneLanePerRank(newRanks, newMinRank, i-1);
				newRanks = (Map<Integer, Set<mxGraphHierarchyNode>>)r[0];
				newMinRank = (Integer)r[1];
				int score = getRanksScore(newRanks, newMinRank);
				if (bestNewRanks == null || bestRanksScore < score)
				{
					bestRanksScore = score;
					bestNewRanks = newRanks;
					bestNewMinRank = newMinRank;
				}
			}
			return new Object[] {bestNewRanks, bestNewMinRank};
		}
		else
		{
			return ensureOneLanePerRank(ranks, minRank, i-1);
		}
		
    }
    
	public void layeringStage()
	{
		model.initialRank();

		Map<Integer, Set<mxGraphHierarchyNode>> ranks = getCellsByRank();
		
		int minRank = 0;
		Object [] r = ensureOneLanePerRank(ranks, minRank, model.maxRank);
		ranks = (Map<Integer, Set<mxGraphHierarchyNode>>)r[0];
		minRank = (Integer)r[1];
		
		if (minRank < 0)
		{
			for (Map.Entry<Object, mxGraphHierarchyNode> e : model.getVertexMapper().entrySet())
			{
				e.getValue().temp[0] -= minRank;
				model.maxRank = Math.max(model.maxRank, e.getValue().temp[0]);
			}
		}
//		
//		ranks = getCellsByRank();
//		for (int i=model.maxRank; i >= 0; i--)
//		{
//			System.out.println(String.format("At rank %d there are %d nodes", i, ranks.get(i).size()));
//		}
		model.fixRanks();
	}
    
  }
  
}
