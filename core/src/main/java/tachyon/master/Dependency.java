package tachyon.master;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectWriter;

import tachyon.Constants;
import tachyon.conf.MasterConf;
import tachyon.io.Utils;
import tachyon.thrift.ClientDependencyInfo;
import tachyon.util.CommonUtils;

/**
 * Describe the lineage between files. Used for recomputation.
 */
public class Dependency extends ImageWriter {
  private static final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);

  /**
   * Create a new dependency from a JSON Element.
   * 
   * @param ele
   *          the JSON element
   * @return the loaded dependency
   * @throws IOException
   */
  static Dependency loadImage(ImageElement ele) throws IOException {
    Dependency dep =
        new Dependency(ele.getInt("depID"), ele.<List<Integer>> get("parentFiles"),
            ele.<List<Integer>> get("childrenFiles"), ele.getString("commandPrefix"),
            ele.getByteBufferList("data"), ele.getString("comment"), ele.getString("framework"),
            ele.getString("frameworkVersion"), ele.<DependencyType> get("dependencyType"),
            ele.<List<Integer>> get("parentDeps"), ele.getLong("creationTimeMs"));
    dep.resetUncheckpointedChildrenFiles(ele.<List<Integer>> get("unCheckpointedChildrenFiles"));

    return dep;
  }

  public final int ID;

  public final long CREATION_TIME_MS;
  public final List<Integer> PARENT_FILES;
  public final List<Integer> CHILDREN_FILES;
  private final Set<Integer> UNCHECKPOINTED_CHILDREN_FILES;
  public final String COMMAND_PREFIX;

  public final List<ByteBuffer> DATA;
  public final String COMMENT;
  public final String FRAMEWORK;

  public final String FRAMEWORK_VERSION;

  public final DependencyType TYPE;
  public final List<Integer> PARENT_DEPENDENCIES;
  private List<Integer> mChildrenDependencies;

  private Set<Integer> mLostFileIds;

  /**
   * Create a new dependency
   * 
   * @param id
   *          The id of the dependency
   * @param parents
   *          The input files' id of the dependency
   * @param children
   *          The output files' id of the dependency
   * @param commandPrefix
   *          The prefix of the command used for recomputation
   * @param data
   *          The list of the data used for recomputation
   * @param comment
   *          The comment of the dependency
   * @param framework
   *          The framework of the dependency, used for recomputation
   * @param frameworkVersion
   *          The version of the framework
   * @param type
   *          The type of the dependency, DependencyType.Wide or DependencyType.Narrow
   * @param parentDependencies
   *          The id of the parents' dependencies
   * @param creationTimeMs
   *          The create time of the dependency, in milliseconds
   */
  public Dependency(int id, List<Integer> parents, List<Integer> children, String commandPrefix,
      List<ByteBuffer> data, String comment, String framework, String frameworkVersion,
      DependencyType type, Collection<Integer> parentDependencies, long creationTimeMs) {
    ID = id;
    CREATION_TIME_MS = creationTimeMs;

    PARENT_FILES = new ArrayList<Integer>(parents.size());
    PARENT_FILES.addAll(parents);
    CHILDREN_FILES = new ArrayList<Integer>(children.size());
    CHILDREN_FILES.addAll(children);
    UNCHECKPOINTED_CHILDREN_FILES = new HashSet<Integer>();
    UNCHECKPOINTED_CHILDREN_FILES.addAll(CHILDREN_FILES);
    COMMAND_PREFIX = commandPrefix;
    DATA = CommonUtils.cloneByteBufferList(data);

    COMMENT = comment;
    FRAMEWORK = framework;
    FRAMEWORK_VERSION = frameworkVersion;

    TYPE = type;

    PARENT_DEPENDENCIES = new ArrayList<Integer>(parentDependencies.size());
    PARENT_DEPENDENCIES.addAll(parentDependencies);
    mChildrenDependencies = new ArrayList<Integer>(0);
    mLostFileIds = new HashSet<Integer>(0);
  }

  /**
   * Add a child dependency, which means one of the children of the current dependency is a parent
   * of the added dependency.
   * 
   * @param childDependencyId
   *          The id of the child dependency to be added
   */
  public synchronized void addChildrenDependency(int childDependencyId) {
    for (int dependencyId : mChildrenDependencies) {
      if (dependencyId == childDependencyId) {
        return;
      }
    }
    mChildrenDependencies.add(childDependencyId);
  }

  /**
   * A file lost. Add it to the dependency.
   * 
   * @param fileId
   *          The id of the lost file
   */
  public synchronized void addLostFile(int fileId) {
    mLostFileIds.add(fileId);
  }

  /**
   * A child file has been checkpointed. Remove it from the uncheckpointed children list.
   * 
   * @param childFileId
   *          The id of the checkpointed child file
   */
  public synchronized void childCheckpointed(int childFileId) {
    UNCHECKPOINTED_CHILDREN_FILES.remove(childFileId);
    LOG.debug("Child got checkpointed " + childFileId + " : " + toString());
  }

  /**
   * Generate a ClientDependencyInfo, which is used for the thrift server.
   * 
   * @return the generated ClientDependencyInfo
   */
  public ClientDependencyInfo generateClientDependencyInfo() {
    ClientDependencyInfo ret = new ClientDependencyInfo();
    ret.id = ID;
    ret.parents = new ArrayList<Integer>(PARENT_FILES.size());
    ret.parents.addAll(PARENT_FILES);
    ret.children = new ArrayList<Integer>(CHILDREN_FILES.size());
    ret.children.addAll(CHILDREN_FILES);
    ret.data = CommonUtils.cloneByteBufferList(DATA);
    return ret;
  }

  /**
   * Get the children dependencies of this dependency. It will return a duplication.
   * 
   * @return the duplication of the children dependencies
   */
  public synchronized List<Integer> getChildrenDependency() {
    List<Integer> ret = new ArrayList<Integer>(mChildrenDependencies.size());
    ret.addAll(mChildrenDependencies);
    return ret;
  }

  /**
   * Get the command used for the recomputation. Note that it will clear the set of lost files' id.
   * 
   * @return the command used for the recomputation
   */
  public synchronized String getCommand() {
    // TODO We should support different types of command in the future.
    // For now, assume there is only one command model.
    StringBuilder sb = new StringBuilder(parseCommandPrefix());
    sb.append(" ").append(MasterConf.get().MASTER_ADDRESS);
    sb.append(" ").append(ID);
    for (int k = 0; k < CHILDREN_FILES.size(); k ++) {
      int id = CHILDREN_FILES.get(k);
      if (mLostFileIds.contains(id)) {
        sb.append(" ").append(k);
      }
    }
    mLostFileIds.clear();
    return sb.toString();
  }

  /**
   * Get the lost files of the dependency. It will return a duplication.
   * 
   * @return the duplication of the lost files' id
   */
  public synchronized List<Integer> getLostFiles() {
    List<Integer> ret = new ArrayList<Integer>();
    ret.addAll(mLostFileIds);
    return ret;
  }

  /**
   * Get the uncheckpointed children files. It will return a duplication.
   * 
   * @return the duplication of the uncheckpointed children files' id
   */
  synchronized List<Integer> getUncheckpointedChildrenFiles() {
    List<Integer> ret = new ArrayList<Integer>(UNCHECKPOINTED_CHILDREN_FILES.size());
    ret.addAll(UNCHECKPOINTED_CHILDREN_FILES);
    return ret;
  }

  /**
   * Return true if the dependency has checkpointed, which means all the children files are
   * checkpointed.
   * 
   * @return true if all the children files are checkpointed, false otherwise
   */
  public synchronized boolean hasCheckpointed() {
    return UNCHECKPOINTED_CHILDREN_FILES.size() == 0;
  }

  /**
   * Return true if it has children dependency.
   * 
   * @return true if it has children dependency, false otherwise
   */
  public synchronized boolean hasChildrenDependency() {
    return !mChildrenDependencies.isEmpty();
  }

  /**
   * Return true if there exists lost file of the dependency.
   * 
   * @return true if the dependency has lost file, false otherwise
   */
  public synchronized boolean hasLostFile() {
    return !mLostFileIds.isEmpty();
  }

  /**
   * Replace the prefix with the specified variable in DependencyVariables.
   * 
   * @return the replaced command
   */
  String parseCommandPrefix() {
    String rtn = COMMAND_PREFIX;
    for (String s : DependencyVariables.VARIABLES.keySet()) {
      rtn = rtn.replace("$" + s, DependencyVariables.VARIABLES.get(s));
    }
    return rtn;
  }

  /**
   * Reset the uncheckpointed children files with the specified input
   * 
   * @param uncheckpointedChildrenFiles
   *          The new uncheckpointed children files' id
   */
  synchronized void resetUncheckpointedChildrenFiles(
      Collection<Integer> uncheckpointedChildrenFiles) {
    UNCHECKPOINTED_CHILDREN_FILES.clear();
    UNCHECKPOINTED_CHILDREN_FILES.addAll(uncheckpointedChildrenFiles);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Dependency[");
    sb.append("ID:").append(ID).append(", CREATION_TIME_MS:").append(CREATION_TIME_MS);
    sb.append(", Parents:").append(PARENT_FILES).append(", Children:").append(CHILDREN_FILES);
    sb.append(", COMMAND_PREFIX:").append(COMMAND_PREFIX);
    sb.append(", PARSED_COMMAND_PREFIX:").append(parseCommandPrefix());
    sb.append(", COMMENT:").append(COMMENT);
    sb.append(", FRAMEWORK:").append(FRAMEWORK);
    sb.append(", FRAMEWORK_VERSION:").append(FRAMEWORK_VERSION);
    sb.append(", PARENT_DEPENDENCIES:").append(PARENT_DEPENDENCIES);
    sb.append(", ChildrenDependencies:").append(mChildrenDependencies);
    sb.append(", UncheckpointedChildrenFiles:").append(UNCHECKPOINTED_CHILDREN_FILES);
    sb.append("]");
    return sb.toString();
  }

  @Override
  public synchronized void writeImage(ObjectWriter objWriter, DataOutputStream dos)
      throws IOException {
    ImageElement ele =
        new ImageElement(ImageElementType.Dependency).withParameter("depID", ID)
            .withParameter("parentFiles", PARENT_FILES)
            .withParameter("childrenFiles", CHILDREN_FILES)
            .withParameter("commandPrefix", COMMAND_PREFIX)
            .withParameter("data", Utils.byteBufferListToBase64(DATA))
            .withParameter("comment", COMMENT).withParameter("framework", FRAMEWORK)
            .withParameter("frameworkVersion", FRAMEWORK_VERSION).withParameter("depType", TYPE)
            .withParameter("parentDeps", PARENT_DEPENDENCIES)
            .withParameter("creationTimeMs", CREATION_TIME_MS)
            .withParameter("unCheckpointedChildrenFiles", getUncheckpointedChildrenFiles());
    writeElement(objWriter, dos, ele);
  }
}