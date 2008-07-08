/*
 * Copyright 2000-2007 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.ide.util.treeView;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.ui.LoadingNode;
import com.intellij.util.Alarm;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Time;
import com.intellij.util.concurrency.WorkerThread;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import com.intellij.util.containers.WeakList;
import com.intellij.util.enumeration.EnumerationCopy;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.util.ui.update.Activatable;
import com.intellij.util.ui.update.UiNotifyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class AbstractTreeBuilder implements Disposable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.util.treeView.AbstractTreeBuilder");

  protected JTree myTree;
  // protected for TestNG
  @SuppressWarnings({"WeakerAccess"}) protected DefaultTreeModel myTreeModel;
  private AbstractTreeStructure myTreeStructure;

  private AbstractTreeUpdater myUpdater;

  private Comparator<NodeDescriptor> myNodeDescriptorComparator;
  private final Comparator<TreeNode> myNodeComparator = new Comparator<TreeNode>() {
    public int compare(TreeNode n1, TreeNode n2) {
      if (isLoadingNode(n1) || isLoadingNode(n2)) return 0;
      NodeDescriptor nodeDescriptor1 = (NodeDescriptor)((DefaultMutableTreeNode)n1).getUserObject();
      NodeDescriptor nodeDescriptor2 = (NodeDescriptor)((DefaultMutableTreeNode)n2).getUserObject();
      return myNodeDescriptorComparator != null
             ? myNodeDescriptorComparator.compare(nodeDescriptor1, nodeDescriptor2)
             : nodeDescriptor1.getIndex() - nodeDescriptor2.getIndex();
    }
  };

  private DefaultMutableTreeNode myRootNode;

  private final HashMap<Object, Object> myElementToNodeMap = new HashMap<Object, Object>();
  private final HashSet<DefaultMutableTreeNode> myUnbuiltNodes = new HashSet<DefaultMutableTreeNode>();
  private TreeExpansionListener myExpansionListener;

  private WorkerThread myWorker = null;
  private ProgressIndicator myProgress;

  private static final int WAIT_CURSOR_DELAY = 100;

  private boolean myDisposed = false;
  // used for searching only
  private final AbstractTreeNode<Object> TREE_NODE_WRAPPER = createSearchingTreeNodeWrapper();

  private boolean myRootNodeWasInitialized = false;

  private final Map<Object, List<NodeAction>> myBackgroundableNodeActions = new HashMap<Object, List<NodeAction>>();

  private boolean myUpdateFromRootRequested;
  private boolean myWasEverShown;
  private boolean myUpdateIfInactive;

  private WeakList<Object> myLoadingParents = new WeakList<Object>();

  private long myClearOnHideDelay = -1;
  private ScheduledExecutorService ourClearanceService;
  private Map<AbstractTreeBuilder, Long> ourBuilder2Countdown = Collections.synchronizedMap(new WeakHashMap<AbstractTreeBuilder, Long>());

  private final List<Runnable> myDeferredSelections = new ArrayList<Runnable>();
  private final List<Runnable> myDeferredExpansions = new ArrayList<Runnable>();

  protected AbstractTreeNode createSearchingTreeNodeWrapper() {
    return new AbstractTreeNodeWrapper();
  }

  public AbstractTreeBuilder(JTree tree,
                             DefaultTreeModel treeModel,
                             AbstractTreeStructure treeStructure,
                             Comparator<NodeDescriptor> comparator) {
    init(tree, treeModel, treeStructure, comparator, true);
  }
  public AbstractTreeBuilder(JTree tree,
                             DefaultTreeModel treeModel,
                             AbstractTreeStructure treeStructure,
                             Comparator<NodeDescriptor> comparator,
                             boolean updateIfInactive) {
    init(tree, treeModel, treeStructure, comparator, updateIfInactive);
  }

  protected AbstractTreeBuilder() {
  }

  protected final void init(JTree tree,
                             DefaultTreeModel treeModel,
                             AbstractTreeStructure treeStructure,
                             Comparator<NodeDescriptor> comparator) {

    init(tree, treeModel, treeStructure, comparator, true);
  }

  protected final void init(JTree tree,
                             DefaultTreeModel treeModel,
                             AbstractTreeStructure treeStructure,
                             Comparator<NodeDescriptor> comparator,
                             boolean updateIfInactive) {
    myTree = tree;
    myTreeModel = treeModel;
    myTree.setModel(myTreeModel);
    setRootNode((DefaultMutableTreeNode)treeModel.getRoot());
    setTreeStructure(treeStructure);
    myNodeDescriptorComparator = comparator;
    myUpdateIfInactive = updateIfInactive;

    myExpansionListener = new MyExpansionListener();
    myTree.addTreeExpansionListener(myExpansionListener);

    setUpdater(createUpdater());
    myProgress = createProgressIndicator();
    Disposer.register(this, getUpdater());

    new UiNotifyConnector(tree, new Activatable() {
      public void showNotify() {
        processShowNotify();
      }

      public void hideNotify() {
        processHideNotify();
      }
    });
  }

  public AbstractTreeBuilder setClearOnHideDelay(final long clearOnHideDelay) {
    myClearOnHideDelay = clearOnHideDelay;
    return this;
  }

  protected void processHideNotify() {
    if (!myWasEverShown) return;

    if (!myBackgroundableNodeActions.isEmpty()) {
      cancelBackgroundLoading();
      myUpdateFromRootRequested = true;
    }

    if (myClearOnHideDelay >= 0) {
      ourBuilder2Countdown.put(this, System.currentTimeMillis() + myClearOnHideDelay);
      initClearanceServiceIfNeeded();
    }
  }

  private void initClearanceServiceIfNeeded() {
    if (ourClearanceService != null) return;

    ourClearanceService = ConcurrencyUtil.newSingleScheduledThreadExecutor("AbstractTreeBuilder's janitor");
    ourClearanceService.scheduleWithFixedDelay(new Runnable() {
      public void run() {
        cleanUpAll();
      }
    }, getJanitorPollPeriod(), getJanitorPollPeriod(), TimeUnit.MILLISECONDS);
  }

  private void cleanUpAll() {
    final long now = System.currentTimeMillis();
    final AbstractTreeBuilder[] builders = ourBuilder2Countdown.keySet().toArray(new AbstractTreeBuilder[ourBuilder2Countdown.size()]);
    for (AbstractTreeBuilder builder : builders) {
      if (builder == null) continue;
      final Long timeToCleanup = ourBuilder2Countdown.get(builder);
      if (timeToCleanup == null) continue;
      if (now >= timeToCleanup.longValue()) {
        ourBuilder2Countdown.remove(builder);
        cleanUp(builder);
      }
    }
  }

  protected void cleanUp(final AbstractTreeBuilder builder) {
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        builder.cleanUp();
      }
    });
  }

  private void disposeClearanceServiceIfNeeded() {
    if (ourClearanceService != null && ourBuilder2Countdown.size() == 0) {
      ourClearanceService.shutdown();
      ourClearanceService = null;
    }
  }

  protected long getJanitorPollPeriod() {
    return Time.SECOND * 10;
  }

  protected void processShowNotify() {
    ourBuilder2Countdown.remove(this);

    if (!myWasEverShown || myUpdateFromRootRequested) {
      if (wasRootNodeInitialized()) {
        updateFromRoot();
      } else {
        initRootNodeNow();
        updateFromRoot();
      }
    }
    myWasEverShown = true;
  }

  /**
   * node descriptor getElement contract is as follows:
   * 1.TreeStructure always returns & recieves "treestructure" element returned by getTreeStructureElement
   * 2.Paths contain "model" element returned by getElement
   */
  protected Object getTreeStructureElement(NodeDescriptor nodeDescriptor) {
    return nodeDescriptor.getElement();
  }

  @Nullable
  protected ProgressIndicator createProgressIndicator() {
    return null;
  }

  protected AbstractTreeUpdater createUpdater() {
    return new AbstractTreeUpdater(this);
  }

  public void dispose() {
    if (myDisposed) return;
    myDisposed = true;
    myTree.removeTreeExpansionListener(myExpansionListener);
    disposeNode(getRootNode());
    myElementToNodeMap.clear();
    getUpdater().cancelAllRequests();
    if (myWorker != null) {
      myWorker.dispose(true);
    }
    TREE_NODE_WRAPPER.setValue(null);
    if (myProgress != null) {
      myProgress.cancel();
    }
    disposeClearanceServiceIfNeeded();

    myTree = null;
    setUpdater(null);
    myWorker = null;
  }

  public boolean isDisposed() {
    return myDisposed;
  }

  protected abstract boolean isAlwaysShowPlus(NodeDescriptor nodeDescriptor);

  protected abstract boolean isAutoExpandNode(NodeDescriptor nodeDescriptor);

  protected boolean isDisposeOnCollapsing(NodeDescriptor nodeDescriptor) {
    return true;
  }

  protected boolean isSmartExpand() {
    return true;
  }

  protected void expandNodeChildren(final DefaultMutableTreeNode node) {
    getTreeStructure().commit();
    getUpdater().addSubtreeToUpdate(node);
    addNodeAction(getElementFor(node), new NodeAction() {
      public void onReady(final DefaultMutableTreeNode node) {
        processSmartExpand(node);
      }
    });
    getUpdater().performUpdate();
  }

  public final AbstractTreeStructure getTreeStructure() {
    return myTreeStructure;
  }

  public final JTree getTree() {
    return myTree;
  }

  @Nullable
  public final
  DefaultMutableTreeNode getNodeForElement(Object element) {
    DefaultMutableTreeNode node = getFirstNode(element);
    if (node != null) {
      LOG.assertTrue(TreeUtil.isAncestor(getRootNode(), node));
      LOG.assertTrue(getRootNode() == myTreeModel.getRoot());
    }
    return node;
  }

  public final DefaultMutableTreeNode getNodeForPath(Object[] path) {
    DefaultMutableTreeNode node = null;
    for (final Object pathElement : path) {
      node = node == null ? getFirstNode(pathElement) : findNodeForChildElement(node, pathElement);
      if (node == null) {
        break;
      }
    }
    return node;
  }

  public final void buildNodeForElement(Object element) {
    getUpdater().performUpdate();
    DefaultMutableTreeNode node = getNodeForElement(element);
    if (node == null) {
      final List<Object> elements = new ArrayList<Object>();
      while (true) {
        element = getTreeStructure().getParentElement(element);
        if (element == null) {
          break;
        }
        elements.add(0, element);
      }

      for (final Object element1 : elements) {
        node = getNodeForElement(element1);
        if (node != null) {
          expand(node);
        }
      }
    }
  }

  public final void buildNodeForPath(Object[] path) {
    getUpdater().performUpdate();
    DefaultMutableTreeNode node = null;
    for (final Object pathElement : path) {
      node = node == null ? getFirstNode(pathElement) : findNodeForChildElement(node, pathElement);
      if (node != null) {
        expand(node);
      }
    }
  }

  public final void setNodeDescriptorComparator(Comparator<NodeDescriptor> nodeDescriptorComparator) {
    myNodeDescriptorComparator = nodeDescriptorComparator;
    List<Object> pathsToExpand = new ArrayList<Object>();
    List<Object> selectionPaths = new ArrayList<Object>();
    TreeBuilderUtil.storePaths(this, getRootNode(), pathsToExpand, selectionPaths, false);
    resortChildren(getRootNode());
    myTreeModel.nodeStructureChanged(getRootNode());
    TreeBuilderUtil.restorePaths(this, pathsToExpand, selectionPaths, false);
  }

  private void resortChildren(DefaultMutableTreeNode node) {
    ArrayList<TreeNode> childNodes = TreeUtil.childrenToArray(node);
    node.removeAllChildren();
    Collections.sort(childNodes, myNodeComparator);
    for (TreeNode childNode1 : childNodes) {
      DefaultMutableTreeNode childNode = (DefaultMutableTreeNode)childNode1;
      node.add(childNode);
      resortChildren(childNode);
    }
  }

  protected final void initRootNode() {
    final Activatable activatable = new Activatable() {
      public void showNotify() {
        if (!myRootNodeWasInitialized) {
          initRootNodeNow();
        }
      }

      public void hideNotify() {
      }
    };

    if (myUpdateIfInactive || ApplicationManager.getApplication().isUnitTestMode()) {
      activatable.showNotify();      
    } else {
      new UiNotifyConnector.Once(myTree, activatable);
    }
  }

  private void initRootNodeNow() {
    if (myRootNodeWasInitialized) return;

    myRootNodeWasInitialized = true;
    Object rootElement = getTreeStructure().getRootElement();
    addNodeAction(rootElement, new NodeAction() {
      public void onReady(final DefaultMutableTreeNode node) {
        processDeferredActions();
      }
    });
    NodeDescriptor nodeDescriptor = getTreeStructure().createDescriptor(rootElement, null);
    getRootNode().setUserObject(nodeDescriptor);
    updateNodeDescriptor(nodeDescriptor);
    if (nodeDescriptor.getElement() != null) {
      createMapping(nodeDescriptor.getElement(), getRootNode());
    }
    addLoadingNode(getRootNode());
    boolean willUpdate = false;
    if (isAutoExpandNode(nodeDescriptor)) {
      willUpdate = myUnbuiltNodes.contains(getRootNode());
      expand(getRootNode());
    }
    if (!willUpdate) {
      updateNodeChildren(getRootNode());
    }
    if (getRootNode().getChildCount() == 0) {
      myTreeModel.nodeChanged(getRootNode());
    }

    processDeferredActions();
  }

  private void processDeferredActions() {
    processDeferredActions(myDeferredSelections);
    processDeferredActions(myDeferredExpansions);
  }

  private void processDeferredActions(List<Runnable> actions) {
    final Runnable[] runnables = actions.toArray(new Runnable[actions.size()]);
    actions.clear();
    for (Runnable runnable : runnables) {
      runnable.run();
    }
  }

  public void updateFromRoot() {
    updateSubtree(getRootNode());
  }

  public final void updateSubtree(DefaultMutableTreeNode node) {
    if (!(node.getUserObject()instanceof NodeDescriptor)) return;
    final TreeState treeState = TreeState.createOn(myTree, node);
    updateNode(node);
    updateNodeChildren(node);
    treeState.applyTo(myTree, node);
  }

  protected void updateNode(DefaultMutableTreeNode node) {
    if (!(node.getUserObject()instanceof NodeDescriptor)) return;
    NodeDescriptor descriptor = (NodeDescriptor)node.getUserObject();
    Object prevElement = descriptor.getElement();
    if (prevElement == null) return;
    boolean changes = updateNodeDescriptor(descriptor);
    if (descriptor.getElement() == null) {
      LOG.assertTrue(false, "element == null, updateSubtree should be invoked for parent! builder=" + this + ", prevElement = " +
                            prevElement + ", node = " + node+"; parentDescriptor="+ descriptor.getParentDescriptor());
    }
    if (changes) {
      updateNodeImageAndPosition(node);
    }
  }

  private void updateNodeChildren(final DefaultMutableTreeNode node) {
    getTreeStructure().commit();
    boolean wasExpanded = myTree.isExpanded(new TreePath(node.getPath()));
    final boolean wasLeaf = node.getChildCount() == 0;

    final NodeDescriptor descriptor = (NodeDescriptor)node.getUserObject();

    if (descriptor == null) return;

    if (myUnbuiltNodes.contains(node)) {
      processUnbuilt(node, descriptor);
      processNodeActionsIfReady(node);
      return;
    }

    if (getTreeStructure().isToBuildChildrenInBackground(getTreeStructureElement(descriptor))) {
      if (queueBackgroundUpdate(node, descriptor)) return;
    }

    Map<Object, Integer> elementToIndexMap = collectElementToIndexMap(descriptor);

    processAllChildren(node, elementToIndexMap);

    ArrayList<TreeNode> nodesToInsert = collectNodesToInsert(descriptor, elementToIndexMap);

    insertNodesInto(nodesToInsert, node);

    updateNodesToInsert(nodesToInsert);

    if (wasExpanded) {
      expand(node);
    }

    if (wasExpanded || wasLeaf) {
      expand(node, descriptor, wasLeaf);
    }

    processNodeActionsIfReady(node);
  }

  private void expand(DefaultMutableTreeNode node) {
    expand(new TreePath(node.getPath()));
  }

  private void expand(final TreePath path) {
    if (path == null) return;
    boolean isLeaf = myTree.getModel().isLeaf(path.getLastPathComponent());
    final TreePath parent = path.getParentPath();
    if (myTree.isExpanded(path) || (isLeaf && parent != null && myTree.isExpanded(parent))) {
      final Object last = path.getLastPathComponent();
      if (last instanceof DefaultMutableTreeNode) {
        processNodeActionsIfReady((DefaultMutableTreeNode)last);
      }
    } else {
      myTree.expandPath(path);
    }
  }

  private void processUnbuilt(final DefaultMutableTreeNode node, final NodeDescriptor descriptor) {
    if (isAlwaysShowPlus(descriptor)) return; // check for isAlwaysShowPlus is important for e.g. changing Show Members state!

    if (getTreeStructure().isToBuildChildrenInBackground(getTreeStructureElement(descriptor))) return; //?

    Object[] children = getTreeStructure().getChildElements(getTreeStructureElement(descriptor));
    if (children.length == 0) {
      for (int i = 0; i < node.getChildCount(); i++) {
        if (isLoadingNode(node.getChildAt(i))) {
          myTreeModel.removeNodeFromParent((MutableTreeNode)node.getChildAt(i));
          break;
        }
      }
      myUnbuiltNodes.remove(node);
    }
  }

  private void updateNodesToInsert(final ArrayList<TreeNode> nodesToInsert) {
    for (TreeNode aNodesToInsert : nodesToInsert) {
      DefaultMutableTreeNode childNode = (DefaultMutableTreeNode)aNodesToInsert;
      addLoadingNode(childNode);
      updateNodeChildren(childNode);
    }
  }

  private boolean processAllChildren(final DefaultMutableTreeNode node, final Map<Object, Integer> elementToIndexMap) {
    ArrayList<TreeNode> childNodes = TreeUtil.childrenToArray(node);
    boolean containsLoading = false;
    for (TreeNode childNode1 : childNodes) {
      DefaultMutableTreeNode childNode = (DefaultMutableTreeNode)childNode1;
      if (isLoadingNode(childNode)) {
        containsLoading = true;
        continue;
      }
      processChildNode(childNode, (NodeDescriptor)childNode.getUserObject(), node, elementToIndexMap);
    }

    return containsLoading;
  }

  private Map<Object, Integer> collectElementToIndexMap(final NodeDescriptor descriptor) {
    Map<Object, Integer> elementToIndexMap = new LinkedHashMap<Object, Integer>();
    Object[] children = getTreeStructure().getChildElements(getTreeStructureElement(descriptor));
    int index = 0;
    for (Object child : children) {
      if (!validateNode(child)) continue;
      elementToIndexMap.put(child, Integer.valueOf(index));
      index++;
    }
    return elementToIndexMap;
  }

  protected boolean validateNode(final Object child) {
    return true;
  }

  private void expand(final DefaultMutableTreeNode node, final NodeDescriptor descriptor, final boolean wasLeaf) {
    final Alarm alarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD);
    alarm.addRequest(new Runnable() {
      public void run() {
        myTree.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
      }
    }, WAIT_CURSOR_DELAY);

    if (wasLeaf && isAutoExpandNode(descriptor)) {
      expand(node);
    }

    ArrayList<TreeNode> nodes = TreeUtil.childrenToArray(node);
    for (TreeNode node1 : nodes) {
      DefaultMutableTreeNode childNode = (DefaultMutableTreeNode)node1;
      if (isLoadingNode(childNode)) continue;
      NodeDescriptor childDescr = (NodeDescriptor)childNode.getUserObject();
      if (isAutoExpandNode(childDescr)) {
        expand(childNode);
      }
    }

    int n = alarm.cancelAllRequests();
    if (n == 0) {
      myTree.setCursor(Cursor.getDefaultCursor());
    }
  }

  public static boolean isLoadingNode(final Object node) {
    return node instanceof LoadingNode;
  }

  private ArrayList<TreeNode> collectNodesToInsert(final NodeDescriptor descriptor, final Map<Object, Integer> elementToIndexMap) {
    ArrayList<TreeNode> nodesToInsert = new ArrayList<TreeNode>();
    for (Map.Entry<Object, Integer> entry : elementToIndexMap.entrySet()) {
      Object child = entry.getKey();
      Integer index = entry.getValue();
      final NodeDescriptor childDescr = getTreeStructure().createDescriptor(child, descriptor);
      //noinspection ConstantConditions
      if (childDescr == null) {
        LOG.error("childDescr == null, treeStructure = " + getTreeStructure() + ", child = " + child);
        continue;
      }
      childDescr.setIndex(index.intValue());
      updateNodeDescriptor(childDescr);
      if (childDescr.getElement() == null) {
        LOG.error("childDescr.getElement() == null, child = " + child + ", builder = " + this);
        continue;
      }
      final DefaultMutableTreeNode childNode = createChildNode(childDescr);
      nodesToInsert.add(childNode);
      createMapping(childDescr.getElement(), childNode);
    }
    return nodesToInsert;
  }

  protected DefaultMutableTreeNode createChildNode(final NodeDescriptor childDescr) {
    return new DefaultMutableTreeNode(childDescr);
  }

  private boolean queueBackgroundUpdate(final DefaultMutableTreeNode node, final NodeDescriptor descriptor) {
    if (isLoadingChildrenFor(node)) return false;

    LoadingNode loadingNode = new LoadingNode(getLoadingNodeText());
    myTreeModel.insertNodeInto(loadingNode, node, node.getChildCount()); // 2 loading nodes - only one will be removed

    myLoadingParents.add(descriptor.getElement());

    Runnable updateRunnable = new Runnable() {
      public void run() {
        updateNodeDescriptor(descriptor);
        Object element = descriptor.getElement();
        if (element == null) return;

        getTreeStructure().getChildElements(getTreeStructureElement(descriptor)); // load children
      }
    };

    Runnable postRunnable = new Runnable() {
      public void run() {
        myLoadingParents.remove(descriptor.getElement());

        updateNodeDescriptor(descriptor);
        Object element = descriptor.getElement();

        if (element != null) {
          myUnbuiltNodes.remove(node);

          for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (isLoadingNode(child)) {
              if (TreeBuilderUtil.isNodeSelected(myTree, node)) {
                myTree.addSelectionPath(new TreePath(myTreeModel.getPathToRoot(node)));
              }
              myTreeModel.removeNodeFromParent((MutableTreeNode)child);
              i--;
            }
          }

          processNodeActionsIfReady(node);
        }
      }
    };
    addTaskToWorker(updateRunnable, true, postRunnable);
    return true;
  }


  private void processNodeActionsIfReady(final DefaultMutableTreeNode node) {
    if (isNodeBeingBuilt(node)) return;

    final Object o = node.getUserObject();
    if (!(o instanceof NodeDescriptor)) return;

    final Object element = ((NodeDescriptor)o).getElement();

    final List<NodeAction> actions = myBackgroundableNodeActions.get(element);
    if (actions != null) {
      myBackgroundableNodeActions.remove(element);
      for (NodeAction each : actions) {
        each.onReady(node);
      }
    }
  }

  private void processSmartExpand(final DefaultMutableTreeNode node) {
    if (isSmartExpand() && node.getChildCount() == 1) { // "smart" expand
      TreeNode childNode = node.getChildAt(0);
      if (isLoadingNode(childNode)) return;
      final TreePath childPath = new TreePath(node.getPath()).pathByAddingChild(childNode);
      expand(childPath);
    }
  }

  private boolean isLoadingChildrenFor(final Object nodeObject) {
    if (!(nodeObject instanceof DefaultMutableTreeNode)) return false;

    DefaultMutableTreeNode node = (DefaultMutableTreeNode)nodeObject;

    int loadingNodes = 0;
    for (int i = 0; i < node.getChildCount(); i++) {
      TreeNode child = node.getChildAt(i);
      if (isLoadingNode(child)) {
        loadingNodes++;
      }
    }
    return loadingNodes > 0 && loadingNodes == node.getChildCount();
  }

  private boolean isParentLoading(Object nodeObject) {
    if (!(nodeObject instanceof DefaultMutableTreeNode)) return false;

    DefaultMutableTreeNode node = (DefaultMutableTreeNode)nodeObject;

    TreeNode eachParent = node.getParent();

    while(eachParent != null) {
      eachParent = eachParent.getParent();
      if (eachParent instanceof DefaultMutableTreeNode) {
        final Object eachElement = getElementFor((DefaultMutableTreeNode)eachParent);
        if (myLoadingParents.contains(eachElement)) return true;
      }
    }

    return false;
  }

  protected String getLoadingNodeText() {
    return IdeBundle.message("progress.searching");
  }

  private void processChildNode(final DefaultMutableTreeNode childNode,
                                final NodeDescriptor childDescr,
                                final DefaultMutableTreeNode node,
                                final Map<Object, Integer> elementToIndexMap) {
    if (childDescr == null) {
      boolean isInMap = myElementToNodeMap.containsValue(childNode);
      LOG.error(
        "childDescr == null, builder=" + this + ", childNode=" + childNode.getClass() + ", isInMap = " + isInMap + ", node = " + node);
      return;
    }
    Object oldElement = childDescr.getElement();
    if (oldElement == null) {
      LOG.error("oldElement == null, builder=" + this + ", childDescr=" + childDescr);
      return;
    }
    boolean changes = updateNodeDescriptor(childDescr);
    Object newElement = childDescr.getElement();
    Integer index = newElement != null ? elementToIndexMap.get(getTreeStructureElement(childDescr)) : null;
    if (index != null) {
      if (childDescr.getIndex() != index.intValue()) {
        changes = true;
      }
      childDescr.setIndex(index.intValue());
    }
    if (index != null && changes) {
      updateNodeImageAndPosition(childNode);
    }
    if (!oldElement.equals(newElement)) {
      removeMapping(oldElement, childNode);
      if (newElement != null) {
        createMapping(newElement, childNode);
      }
    }

    if (index == null) {
      int selectedIndex = -1;
      if (TreeBuilderUtil.isNodeOrChildSelected(myTree, childNode)) {
        selectedIndex = node.getIndex(childNode);
      }

      myTreeModel.removeNodeFromParent(childNode);
      disposeNode(childNode);

      if (selectedIndex >= 0) {
        if (node.getChildCount() > 0) {
          if (node.getChildCount() > selectedIndex) {
            TreeNode newChildNode = node.getChildAt(selectedIndex);
            myTree.addSelectionPath(new TreePath(myTreeModel.getPathToRoot(newChildNode)));
          }
          else {
            TreeNode newChild = node.getChildAt(node.getChildCount() - 1);
            myTree.addSelectionPath(new TreePath(myTreeModel.getPathToRoot(newChild)));
          }
        }
        else {
          myTree.addSelectionPath(new TreePath(myTreeModel.getPathToRoot(node)));
        }
      }
    }
    else {
      elementToIndexMap.remove(getTreeStructureElement(childDescr));
      updateNodeChildren(childNode);
    }

    if (node.equals(getRootNode())) {
      myTreeModel.nodeChanged(getRootNode());
    }
  }

  protected boolean updateNodeDescriptor(final NodeDescriptor descriptor) {
    return descriptor.update();
  }

  private void addLoadingNode(final DefaultMutableTreeNode node) {
    final NodeDescriptor descriptor = (NodeDescriptor)node.getUserObject();
    if (!isAlwaysShowPlus(descriptor)) {
      if (getTreeStructure().isToBuildChildrenInBackground(getTreeStructureElement(descriptor))) {
        final boolean[] hasNoChildren = new boolean[1];
        Runnable runnable = new Runnable() {
          public void run() {
            updateNodeDescriptor(descriptor);
            Object element = getTreeStructureElement(descriptor);
            if (element == null) return;

            Object[] children = getTreeStructure().getChildElements(element);
            hasNoChildren[0] = children.length == 0;
          }
        };

        Runnable postRunnable = new Runnable() {
          public void run() {
            if (hasNoChildren[0]) {
              updateNodeDescriptor(descriptor);
              Object element = descriptor.getElement();
              if (element != null) {
                DefaultMutableTreeNode node = getNodeForElement(element);
                if (node != null) {
                  expand(node);
                }
              }
            }
          }
        };

        addTaskToWorker(runnable, false, postRunnable);
      }
      else {
        Object[] children = getTreeStructure().getChildElements(getTreeStructureElement(descriptor));
        if (children.length == 0) return;
      }
    }

    myTreeModel.insertNodeInto(new LoadingNode(), node, 0);
    myUnbuiltNodes.add(node);
  }

  protected void addTaskToWorker(final Runnable runnable, boolean first, final Runnable postRunnable) {
    Runnable runnable1 = new Runnable() {
      public void run() {
        try {
          Runnable runnable2 = new Runnable() {
            public void run() {
              ApplicationManager.getApplication().runReadAction(runnable);
              if (postRunnable != null) {
                ApplicationManager.getApplication().invokeLater(postRunnable, ModalityState.stateForComponent(myTree));
              }
            }
          };
          if (myProgress != null) {
            ProgressManager.getInstance().runProcess(runnable2, myProgress);
          }
          else {
            runnable2.run();
          }
        }
        catch (ProcessCanceledException e) {
          //ignore
        }
      }
    };

    if (myWorker == null || myWorker.isDisposed()) {
      myWorker = new WorkerThread("AbstractTreeBuilder.Worker", 1);
      myWorker.start();
      if (first) {
        myWorker.addTaskFirst(runnable1);
      }
      else {
        myWorker.addTask(runnable1);
      }
      myWorker.dispose(false);
    }
    else {
      if (first) {
        myWorker.addTaskFirst(runnable1);
      }
      else {
        myWorker.addTask(runnable1);
      }
    }
  }

  private void updateNodeImageAndPosition(final DefaultMutableTreeNode node) {
    if (!(node.getUserObject()instanceof NodeDescriptor)) return;
    NodeDescriptor descriptor = (NodeDescriptor)node.getUserObject();
    if (descriptor.getElement() == null) return;
    DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode)node.getParent();
    if (parentNode != null) {
      int oldIndex = parentNode.getIndex(node);

      int newIndex = 0;
      for (int i = 0; i < parentNode.getChildCount(); i++) {
        DefaultMutableTreeNode node1 = (DefaultMutableTreeNode)parentNode.getChildAt(i);
        if (node == node1) continue;
        if (node1.getUserObject()instanceof NodeDescriptor && ((NodeDescriptor)node1.getUserObject()).getElement() == null) continue;
        if (myNodeComparator.compare(node, node1) > 0) newIndex++;
      }

      if (oldIndex != newIndex) {
        List<Object> pathsToExpand = new ArrayList<Object>();
        List<Object> selectionPaths = new ArrayList<Object>();
        TreeBuilderUtil.storePaths(this, node, pathsToExpand, selectionPaths, false);
        myTreeModel.removeNodeFromParent(node);
        myTreeModel.insertNodeInto(node, parentNode, newIndex);
        TreeBuilderUtil.restorePaths(this, pathsToExpand, selectionPaths, false);
      }
      else {
        myTreeModel.nodeChanged(node);
      }
    }
    else {
      myTreeModel.nodeChanged(node);
    }
  }

  public DefaultTreeModel getTreeModel() {
    return myTreeModel;
  }

  private void insertNodesInto(ArrayList<TreeNode> nodes, DefaultMutableTreeNode parentNode) {
    if (nodes.isEmpty()) return;

    nodes = new ArrayList<TreeNode>(nodes);
    Collections.sort(nodes, myNodeComparator);

    ArrayList<TreeNode> all = TreeUtil.childrenToArray(parentNode);
    all.addAll(nodes);
    Collections.sort(all, myNodeComparator);

    int[] indices = new int[nodes.size()];
    int idx = 0;
    for (int i = 0; i < nodes.size(); i++) {
      TreeNode node = nodes.get(i);
      while (all.get(idx) != node) idx++;
      indices[i] = idx;
      parentNode.insert((MutableTreeNode)node, idx);
    }

    myTreeModel.nodesWereInserted(parentNode, indices);
  }

  private void disposeNode(DefaultMutableTreeNode node) {
    if (node.getChildCount() > 0) {
      for (DefaultMutableTreeNode _node = (DefaultMutableTreeNode)node.getFirstChild(); _node != null; _node = _node.getNextSibling()) {
        disposeNode(_node);
      }
    }
    if (isLoadingNode(node)) return;
    NodeDescriptor descriptor = (NodeDescriptor)node.getUserObject();
    if (descriptor == null) return;
    final Object element = descriptor.getElement();
    removeMapping(element, node);
    node.setUserObject(null);
    node.removeAllChildren();
  }

  public void addSubtreeToUpdate(final DefaultMutableTreeNode root) {
    addSubtreeToUpdate(root, null);
  }
  public void addSubtreeToUpdate(final DefaultMutableTreeNode root, Runnable runAfterUpdate) {
    getUpdater().runAfterUpdate(runAfterUpdate);
    getUpdater().addSubtreeToUpdate(root);
  }

  public boolean wasRootNodeInitialized() {
    return myRootNodeWasInitialized;
  }

  public void select(final Object[] elements, @Nullable final Runnable onDone) {
    if (wasRootNodeInitialized()) {
      final int[] originalRows = myTree.getSelectionRows();
      myTree.clearSelection();
      addNext(elements, 0, onDone, originalRows);
    } else {
      myDeferredSelections.clear();
      myDeferredSelections.add(new Runnable() {
        public void run() {
          select(elements, onDone);
        }
      });
    }
  }

  private void addNext(final Object[] elements, final int i, @Nullable final Runnable onDone, final int[] originalRows) {
    if (i >= elements.length) {
      if (myTree.isSelectionEmpty()) {
        myTree.setSelectionRows(originalRows);
      }
      if (onDone != null) {
        onDone.run();
      }
    }
    else {
      _select(elements[i], new Runnable() {
        public void run() {
          addNext(elements, i + 1, onDone, originalRows);
        }
      }, true);
    }
  }

  public void select(final Object element, @Nullable final Runnable onDone) {
    select(element, onDone, false);
  }

  public void select(final Object element, @Nullable final Runnable onDone, boolean addToSelection) {
    _select(element, onDone, addToSelection);
  }


  private void _select(final Object element, final Runnable onDone, final boolean addToSelection) {
    final Runnable _onDone = new Runnable() {
      public void run() {
        final DefaultMutableTreeNode toSelect = getNodeForElement(element);
        if (toSelect == null) return;
        final int row = myTree.getRowForPath(new TreePath(toSelect.getPath()));
        TreeUtil.showAndSelect(myTree, row - 2, row + 2, row, -1, addToSelection);
        if (onDone != null) {
          onDone.run();
        }
      }
    };
    _expand(element, _onDone, true);
  }

  public void expand(final Object element, @Nullable final Runnable onDone) {
    _expand(element, onDone == null ? new EmptyRunnable() : onDone, false);
  }

  private void _expand(final Object element, @NotNull final Runnable onDone, final boolean parentsOnly) {
    if (wasRootNodeInitialized()) {
      List<Object> kidsToExpand = new ArrayList<Object>();
      Object eachElement = element;
      DefaultMutableTreeNode firstVisible;
      while(true) {
        firstVisible = getNodeForElement(eachElement);
        if (eachElement != element || !parentsOnly) {
          kidsToExpand.add(eachElement);
        }
        if (firstVisible != null) break;
        eachElement = getTreeStructure().getParentElement(eachElement);
        if (eachElement == null) {
          firstVisible = null;
          break;
        }
      }

      if (firstVisible == null) {
        onDone.run();
      }

      processExpand(firstVisible, kidsToExpand, kidsToExpand.size() - 1, onDone);
    } else {
      myDeferredExpansions.add(new Runnable() {
        public void run() {
          _expand(element, onDone, parentsOnly);
        }
      });
    }
  }

  private void processExpand(final DefaultMutableTreeNode toExpand, final List kidsToExpand, final int expandIndex, @NotNull final Runnable onDone) {
    final Object element = getElementFor(toExpand);
    if (element == null) return;

    addNodeAction(element, new NodeAction() {
      public void onReady(final DefaultMutableTreeNode node) {
        if (node.getChildCount() >= 0 && !myTree.isExpanded(new TreePath(node.getPath()))) {
          expand(node);
        }

        if (expandIndex < 0) {
          onDone.run();
          return;
        }

        final DefaultMutableTreeNode nextNode = getNodeForElement(kidsToExpand.get(expandIndex));
        if (nextNode != null) {
          processExpand(nextNode, kidsToExpand, expandIndex - 1, onDone);
        } else {
          onDone.run();
        }
      }
    });

    expand(toExpand);
  }


  @Nullable
  private static Object getElementFor(DefaultMutableTreeNode node) {
    if (node != null) {
      final Object o = node.getUserObject();
      if (o instanceof NodeDescriptor) {
        return ((NodeDescriptor)o).getElement();
      }
    }

    return null;
  }

  public final boolean isNodeBeingBuilt(final TreePath path) {
    return isNodeBeingBuilt(path.getLastPathComponent());
  }

  public final boolean isNodeBeingBuilt(Object nodeObject) {
    return isParentLoading(nodeObject) || isLoadingChildrenFor(nodeObject);
  }

  public void setTreeStructure(final AbstractTreeStructure treeStructure) {
    myTreeStructure = treeStructure;
  }

  public AbstractTreeUpdater getUpdater() {
    return myUpdater;
  }

  public void setUpdater(final AbstractTreeUpdater updater) {
    myUpdater = updater;
  }

  public DefaultMutableTreeNode getRootNode() {
    return myRootNode;
  }

  public void setRootNode(final DefaultMutableTreeNode rootNode) {
    myRootNode = rootNode;
  }

  private class MyExpansionListener implements TreeExpansionListener {
    public void treeExpanded(TreeExpansionEvent event) {
      TreePath path = event.getPath();
      final DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
      if (!myUnbuiltNodes.contains(node)) return;
      myUnbuiltNodes.remove(node);
      final Alarm alarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD);
      alarm.addRequest(new Runnable() {
        public void run() {
          myTree.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }
      }, WAIT_CURSOR_DELAY);

      expandNodeChildren(node);

      for (int i = 0; i < node.getChildCount(); i++) {
        if (isLoadingNode(node.getChildAt(i))) {
          myTreeModel.removeNodeFromParent((MutableTreeNode)node.getChildAt(i));
        }
      }

      int n = alarm.cancelAllRequests();
      if (n == 0) {
        myTree.setCursor(Cursor.getDefaultCursor());
      }

      processSmartExpand(node);
    }

    public void treeCollapsed(TreeExpansionEvent e) {
      TreePath path = e.getPath();
      final DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
      if (isSelectionInside(node)) {
        // when running outside invokeLater, in EJB view just collapsed node get expanded again (bug 4585)
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            if (myDisposed) return;
            myTree.addSelectionPath(new TreePath(myTreeModel.getPathToRoot(node)));
          }
        });
      }
      if (!(node.getUserObject()instanceof NodeDescriptor)) return;
      NodeDescriptor descriptor = (NodeDescriptor)node.getUserObject();
      if (isDisposeOnCollapsing(descriptor)) {
        removeChildren(node);
        addLoadingNode(node);
        if (node.equals(getRootNode())) {
          myTree.addSelectionPath(new TreePath(getRootNode().getPath()));
        }
        else {
          myTreeModel.reload(node);
        }
      }
    }

    private void removeChildren(DefaultMutableTreeNode node) {
      EnumerationCopy copy = new EnumerationCopy(node.children());
      while (copy.hasMoreElements()) {
        disposeNode((DefaultMutableTreeNode)copy.nextElement());
      }
      node.removeAllChildren();
      myTreeModel.nodeStructureChanged(node);
    }

    private boolean isSelectionInside(DefaultMutableTreeNode parent) {
      TreePath path = new TreePath(myTreeModel.getPathToRoot(parent));
      TreePath[] paths = myTree.getSelectionPaths();
      if (paths == null) return false;
      for (TreePath path1 : paths) {
        if (path.isDescendant(path1)) return true;
      }
      return false;
    }
  }

  private void createMapping(Object element, DefaultMutableTreeNode node) {
    if (!myElementToNodeMap.containsKey(element)) {
      myElementToNodeMap.put(element, node);
    }
    else {
      final Object value = myElementToNodeMap.get(element);
      final List<DefaultMutableTreeNode> nodes;
      if (value instanceof DefaultMutableTreeNode) {
        nodes = new ArrayList<DefaultMutableTreeNode>();
        nodes.add((DefaultMutableTreeNode)value);
        myElementToNodeMap.put(element, nodes);
      }
      else {
        nodes = (List<DefaultMutableTreeNode>)value;
      }
      nodes.add(node);
    }
  }

  private void removeMapping(Object element, DefaultMutableTreeNode node) {
    final Object value = myElementToNodeMap.get(element);
    if (value == null) {
      return;
    }
    if (value instanceof DefaultMutableTreeNode) {
      if (value.equals(node)) {
        myElementToNodeMap.remove(element);
      }
    }
    else {
      List<DefaultMutableTreeNode> nodes = (List<DefaultMutableTreeNode>)value;
      final boolean reallyRemoved = nodes.remove(node);
      if (reallyRemoved) {
        if (nodes.isEmpty()) {
          myElementToNodeMap.remove(element);
        }
      }
    }
  }

  private DefaultMutableTreeNode getFirstNode(Object element) {
    final Object value = findNodeByElement(element);
    if (value == null) {
      return null;
    }
    if (value instanceof DefaultMutableTreeNode) {
      return (DefaultMutableTreeNode)value;
    }
    final List<DefaultMutableTreeNode> nodes = (List<DefaultMutableTreeNode>)value;
    return nodes.isEmpty() ? null : nodes.get(0);
  }

  protected Object findNodeByElement(Object element) {
    if (myElementToNodeMap.containsKey(element)) {
      return myElementToNodeMap.get(element);
    }

    try {
      TREE_NODE_WRAPPER.setValue(element);
      return myElementToNodeMap.get(TREE_NODE_WRAPPER);
    }
    finally {
      TREE_NODE_WRAPPER.setValue(null);
    }
  }

  private DefaultMutableTreeNode findNodeForChildElement(DefaultMutableTreeNode parentNode, Object element) {
    final Object value = myElementToNodeMap.get(element);
    if (value == null) {
      return null;
    }

    if (value instanceof DefaultMutableTreeNode) {
      final DefaultMutableTreeNode elementNode = (DefaultMutableTreeNode)value;
      return parentNode.equals(elementNode.getParent()) ? elementNode : null;
    }

    final List<DefaultMutableTreeNode> allNodesForElement = (List<DefaultMutableTreeNode>)value;
    for (final DefaultMutableTreeNode elementNode : allNodesForElement) {
      if (parentNode.equals(elementNode.getParent())) {
        return elementNode;
      }
    }

    return null;
  }

  private static class AbstractTreeNodeWrapper extends AbstractTreeNode<Object> {
    public AbstractTreeNodeWrapper() {
      super(null, null);
    }

    @NotNull
    public Collection<AbstractTreeNode> getChildren() {
      return Collections.emptyList();
    }

    public void update(PresentationData presentation) {
    }
  }

  public void cancelBackgroundLoading() {
    if (myWorker != null) {
      myWorker.cancelTasks();
    }
    myBackgroundableNodeActions.clear();
  }

  private void addNodeAction(Object element, NodeAction action) {
    List<NodeAction> list = myBackgroundableNodeActions.get(element);
    if (list == null) {
      list = new ArrayList<NodeAction>();
      myBackgroundableNodeActions.put(element, list);
    }
    list.add(action);
  }


  interface NodeAction {
    void onReady(DefaultMutableTreeNode node);
  }

  public void cleanUp() {
    if (myDisposed) return;

    final Object[] toSelect = addPaths(myTree.getSelectionPaths());
    final Object[] toExpand = addPaths(myTree.getExpandedDescendants(new TreePath(myTreeModel.getRoot())));

    myTree.collapsePath(new TreePath(myTree.getModel().getRoot()));
    getRootNode().removeAllChildren();

    myRootNodeWasInitialized = false;
    myBackgroundableNodeActions.clear();
    myElementToNodeMap.clear();
    myDeferredSelections.clear();
    myDeferredExpansions.clear();
    myLoadingParents.clear();
    myUnbuiltNodes.clear();
    myUpdateFromRootRequested = true;

    if (myWorker != null) {
      Disposer.dispose(myWorker);
      myWorker = null;
    }

    myTree.invalidate();

    select(toSelect, new Runnable() {
      public void run() {
        for (Object each : toExpand) {
          expand(each, null);
        }
      }
    });
  }

  private Object[] addPaths(Object[] elements) {
    ArrayList elementArray = new ArrayList();
    if (elements != null) {
      elementArray.addAll(Arrays.asList(elements));
    }

    return addPaths(elementArray);
  }

  private Object[] addPaths(Enumeration elements) {
    ArrayList elementArray = new ArrayList();
    if (elements != null) {
      while (elements.hasMoreElements()) {
        Object each = elements.nextElement();
        elementArray.add(each);
      }
    }

    return addPaths(elementArray);
  }

  private Object[] addPaths(Collection elements) {
    ArrayList target = new ArrayList();

    if (elements != null) {
      for (Object each : elements) {
        final Object node = ((TreePath)each).getLastPathComponent();
        if (node instanceof DefaultMutableTreeNode) {
          final Object descriptor = ((DefaultMutableTreeNode)node).getUserObject();
          if (descriptor instanceof NodeDescriptor) {
            final Object element = ((NodeDescriptor)descriptor).getElement();
            if (element != null) {
              target.add(element);
            }
          }
        }
      }
    }
    return target.toArray(new Object[target.size()]);
  }

}
