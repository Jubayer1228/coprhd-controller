/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.block;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DiscoveredSystemObject;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.ProtectionSystem;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.locking.LockTimeoutValue;
import com.emc.storageos.locking.LockType;
import com.emc.storageos.protectioncontroller.ProtectionExportController;
import com.emc.storageos.protectioncontroller.impl.recoverpoint.RPDeviceExportController;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.impl.ControllerLockingUtil;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowException;
import com.emc.storageos.workflow.WorkflowRestartedException;
import com.emc.storageos.workflow.WorkflowService;
import com.google.common.base.Joiner;

public class ExportWorkflowUtils {

    private static final Logger _log =
            LoggerFactory.getLogger(ExportWorkflowUtils.class);
    private DbClient _dbClient;
    private ExportWorkflowEntryPoints _exportWfEntryPoints;
    private WorkflowService _workflowSvc;

    public void setDbClient(DbClient dbc) {
        _dbClient = dbc;
    }

    public WorkflowService getWorkflowService() {
        return _workflowSvc;
    }

    public void setWorkflowService(WorkflowService workflowService) {
        _workflowSvc = workflowService;
    }

    public void setExportWorkflowEntryPoints(ExportWorkflowEntryPoints entryPoints) {
        _exportWfEntryPoints = entryPoints;
    }

    public String generateExportGroupCreateWorkflow(Workflow workflow, String wfGroupId,
            String waitFor, URI storage,
            URI export,
            Map<URI, Integer> volumeMap,
            List<URI> initiatorURIs)
            throws WorkflowException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storage);

        // Filter the addedInitiators for non VPLEX system by the Export Group varray.
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, export);
        initiatorURIs = ExportUtils.filterNonVplexInitiatorsByExportGroupVarray(
                exportGroup, initiatorURIs, storage, _dbClient);

        Workflow.Method method =
                ExportWorkflowEntryPoints.exportGroupCreateMethod(storage, export,
                        volumeMap, initiatorURIs);

        Workflow.Method rollback =
                ExportWorkflowEntryPoints.exportGroupDeleteMethod(storage, export);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Creating export on storage array %s (%s)",
                        storageSystem.getNativeGuid(), storage.toString()),
                storageSystem, method, rollback, waitFor, null);
    }

    /**
     * Creates the workflow for one export mask (storage system) for an update export
     * group call. It creates a single step in the main workflow that wraps a workflow
     * with necessary steps to:
     * <ol>
     * <li>add block objects (volumes/snapshots)</li>
     * <li>remove volumes</li>
     * <li>add initiators</li>
     * <li>remove initiators</li>
     * </ol>
     * The steps are created based on the diff between the current and the requested for
     * the storage system export mask
     *
     * @param workflow the main workflow
     * @param wfGroupId the workflow group Id, if any
     * @param waitFor the id of a step on which this workflow has to wait, if any
     * @param exportGroupUri the export group being updated
     * @param exportMask the export mask for the storage system
     * @param addedBlockObjects the map of block objects to be added
     * @param removedBlockObjects the map of block objects to be removed
     * @param addedInitiators the new list of initiators to be added
     * @param removedInitiators the new list of initiators to be removed
     * @param blockStorageControllerUri the block storage controller. This will always
     *            be used for adding/removing initiators as we
     *            do not want a protection controller doing this.
     * @param storageUri the storage controller used to perform the export update.
     *            This can be either a block storage controller or protection
     *            controller.
     * @return the id of the wrapper step that was added to main workflow
     * @throws IOException
     * @throws WorkflowException
     * @throws WorkflowRestartedException
     */
    public String generateExportGroupUpdateWorkflow(Workflow workflow, String wfGroupId,
            String waitFor,
            URI exportGroupUri,
            ExportMask exportMask,
            Map<URI, Integer> addedBlockObjects,
            Map<URI, Integer> removedBlockObjects,
            List<URI> addedInitiators, List<URI> removedInitiators, URI blockStorageControllerUri)
            throws IOException, WorkflowException, WorkflowRestartedException {

        // Filter the addedInitiators for non VPLEX system by the Export Group varray.
        ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupUri);
        addedInitiators = ExportUtils.filterNonVplexInitiatorsByExportGroupVarray(
                exportGroup, addedInitiators, blockStorageControllerUri, _dbClient);

        if (allCollectionsAreEmpty(addedBlockObjects, removedBlockObjects, addedInitiators, removedInitiators)) {
            _log.info(String.format("There is no export updated required for %s", blockStorageControllerUri.toString()));
            return null;
        }

        // We would rather the task be the child stepID of the parent workflow's stepID.
        // This helps us to preserve parent/child relationships.
        String exportGroupUpdateStepId = workflow.createStepId();
        Workflow storageWorkflow = newWorkflow("storageSystemExportGroupUpdate", false, exportGroupUpdateStepId);
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, blockStorageControllerUri);
        String stepId = null;

        // We willLock the host/storage system duples necessary for the workflows.
        // There are two possibilities about locking. Here we just generate the lockKeys.
        List<URI> lockedInitiatorURIs = new ArrayList<URI>();
        lockedInitiatorURIs.addAll(addedInitiators);
        lockedInitiatorURIs.addAll(StringSetUtil.stringSetToUriList(exportGroup.getInitiators()));
        List<String> lockKeys = ControllerLockingUtil
                .getHostStorageLockKeys(_dbClient,
                        ExportGroup.ExportGroupType.valueOf(exportGroup.getType()),
                        lockedInitiatorURIs, blockStorageControllerUri);

        // for initiators do add first and then remove to avoid having
        // the export mask getting deleted when all existing initiators
        // are getting replaced.
        if (addedInitiators != null && !addedInitiators.isEmpty()) {
            stepId = generateExportGroupAddInitiators(storageWorkflow, null,
                    stepId, exportGroupUri, blockStorageControllerUri, addedInitiators);
        }
        if (removedInitiators != null && !removedInitiators.isEmpty()) {
            stepId = generateExportGroupRemoveInitiators(storageWorkflow, null,
                    stepId, exportGroupUri, blockStorageControllerUri, removedInitiators);
        }
        // for volumes we must do remove first in case the same lun id
        // will be used by one of the added volumes. This may result in
        // the export mask getting deleted and then created. If this
        // ends being a problem, we would need to tackle this issue
        if (removedBlockObjects != null && !removedBlockObjects.isEmpty()) {
            Map<URI, Integer> objectsToRemove = new HashMap<URI, Integer>(removedBlockObjects);

            ProtectionExportController protectionExportController = getProtectionExportController();
            stepId = protectionExportController.addStepsForExportGroupRemoveVolumes(storageWorkflow, null, stepId, exportGroupUri,
                    objectsToRemove, blockStorageControllerUri);

            if (!objectsToRemove.isEmpty()) {
                // Unexport the remaining block objects.
                _log.info(String.format("Generating exportGroupRemoveVolumes step for objects %s associated with storage system [%s]",
                        objectsToRemove, blockStorageControllerUri));
                List<URI> objectsToRemoveList = new ArrayList<URI>(objectsToRemove.keySet());
                stepId = generateExportGroupRemoveVolumes(storageWorkflow,
                        null, stepId, blockStorageControllerUri, exportGroupUri,
                        objectsToRemoveList);
            }
        }
        
        if (addedBlockObjects != null && !addedBlockObjects.isEmpty()) {
            Map<URI, Integer> objectsToAdd = new HashMap<URI, Integer>(addedBlockObjects);

            ProtectionExportController protectionExportController = getProtectionExportController();
            stepId = protectionExportController.addStepsForExportGroupAddVolumes(storageWorkflow, null, stepId, exportGroupUri,
                    objectsToAdd, blockStorageControllerUri);

            if (!objectsToAdd.isEmpty()) {
                // Export the remaining block objects.
                _log.info(String.format("Generating exportGroupAddVolumes step for objects %s associated with storage system [%s]",
                        objectsToAdd.keySet(), blockStorageControllerUri));
                stepId = generateExportGroupAddVolumes(storageWorkflow, null,
                        stepId, blockStorageControllerUri, exportGroupUri, objectsToAdd);
            }
        }

        if (exportMask == null) {
            if (addedInitiators == null) {
                addedInitiators = new ArrayList<URI>();
            }
            if (addedInitiators.isEmpty()) {
                addedInitiators.addAll(getInitiators(exportGroup));
            }

            // Add block volumes already in the export group
            if (exportGroup.getVolumes() != null) {
                for (String key : exportGroup.getVolumes().keySet()) {
                    BlockObject bobject = BlockObject.fetch(_dbClient, URI.create(key));
                    if (bobject.getStorageController().equals(blockStorageControllerUri)) {
                        addedBlockObjects.put(URI.create(key), Integer.valueOf(exportGroup.getVolumes().get(key)));
                    }
                }
            }

            // Acquire locks for the parent workflow.
            boolean acquiredLocks = getWorkflowService().acquireWorkflowLocks(
                    workflow, lockKeys, LockTimeoutValue.get(LockType.EXPORT_GROUP_OPS));
            if (!acquiredLocks) {
                throw DeviceControllerException.exceptions.failedToAcquireLock(lockKeys.toString(),
                        "ExportMaskUpdate: " + exportGroup.getLabel());
            }

            Map<URI, Integer> objectsToAdd = new HashMap<URI, Integer>(addedBlockObjects);

            ProtectionExportController protectionController = getProtectionExportController();
            waitFor = protectionController.addStepsForExportGroupCreate(workflow, wfGroupId, waitFor, exportGroupUri, objectsToAdd,
                    blockStorageControllerUri,
                    addedInitiators);

            if (!objectsToAdd.isEmpty()) {
                // There are no export BlockObjects tied to the current storage system that have an associated protection
                // system. We can just create a step to call the block controller directly for export group create.
                _log.info(String.format(
                        "Generating exportGroupCreate steps for objects %s associated with storage system [%s]",
                        objectsToAdd, blockStorageControllerUri));

                // Add the new block objects to the existing ones and send all down
                waitFor = generateExportGroupCreateWorkflow(workflow, wfGroupId, waitFor,
                        blockStorageControllerUri, exportGroupUri, addedBlockObjects, addedInitiators);
            }

            return waitFor;
        }

        try {
            // Acquire locks for the storageWorkflow which is started just below.
            boolean acquiredLocks = getWorkflowService().acquireWorkflowLocks(
                    storageWorkflow, lockKeys, LockTimeoutValue.get(LockType.EXPORT_GROUP_OPS));
            if (!acquiredLocks) {
                throw DeviceControllerException.exceptions.failedToAcquireLock(lockKeys.toString(),
                        "ExportMaskUpdate: " + exportMask.getMaskName());
            }

            // There will not be a rollback step for the overall update instead
            // the code allows the user to retry update as needed.
            Workflow.Method method =
                    ExportWorkflowEntryPoints.exportGroupUpdateMethod(blockStorageControllerUri, exportGroupUri, storageWorkflow);
            return newWorkflowStep(workflow, wfGroupId,
                    String.format("Updating export on storage array %s (%s)",
                            storageSystem.getNativeGuid(), blockStorageControllerUri.toString()),
                    storageSystem, method, null, waitFor, exportGroupUpdateStepId);
        } catch (Exception ex) {
            getWorkflowService().releaseAllWorkflowLocks(storageWorkflow);
            throw ex;
        }
    }

    public String generateExportGroupDeleteWorkflow(Workflow workflow, String wfGroupId,
            String waitFor, URI storage,
            URI export)
            throws WorkflowException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storage);

        Workflow.Method method =
                ExportWorkflowEntryPoints.exportGroupDeleteMethod(storage, export);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Deleting export on storage array %s (%s)",
                        storageSystem.getNativeGuid(), storage.toString()),
                storageSystem, method, null, waitFor, null);
    }

    public String generateExportGroupAddVolumes(Workflow workflow, String wfGroupId,
            String waitFor, URI storage,
            URI export, Map<URI, Integer> volumeMap)
            throws WorkflowException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storage);

        Workflow.Method method =
                ExportWorkflowEntryPoints.exportAddVolumesMethod(storage, export,
                        volumeMap);

        List<URI> volumeList = new ArrayList<URI>();
        volumeList.addAll(volumeMap.keySet());

        Workflow.Method rollback =
                ExportWorkflowEntryPoints.exportRemoveVolumesMethod(storage, export,
                        volumeList);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Adding volumes to export on storage array %s (%s)",
                        storageSystem.getNativeGuid(), storage.toString()),
                storageSystem, method, rollback, waitFor, null);
    }

    public String generateExportGroupRemoveVolumes(Workflow workflow, String wfGroupId,
            String waitFor, URI storage,
            URI export, List<URI> volumes)
            throws WorkflowException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storage);

        Workflow.Method method =
                ExportWorkflowEntryPoints.exportRemoveVolumesMethod(storage, export,
                        volumes);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Removing volumes from export on storage array %s (%s)",
                        storageSystem.getNativeGuid(), storage.toString()),
                storageSystem, method, null, waitFor, null);
    }

    public String generateExportGroupRemoveInitiators(Workflow workflow,
            String wfGroupId, String waitFor,
            URI export, URI storage,
            List<URI> initiatorURIs)
            throws WorkflowException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storage);

        Workflow.Method method =
                ExportWorkflowEntryPoints.exportRemoveInitiatorsMethod(storage, export,
                        initiatorURIs);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Removing initiators from export on storage array %s (%s)",
                        storageSystem.getNativeGuid(), storage.toString()),
                storageSystem, method, null, waitFor, null);
    }

    public String generateExportGroupAddInitiators(Workflow workflow, String wfGroupId,
            String waitFor, URI export, URI storage,
            List<URI> initiatorURIs)
            throws WorkflowException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storage);

        Workflow.Method method =
                ExportWorkflowEntryPoints.exportAddInitiatorsMethod(storage, export,
                        initiatorURIs);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Adding initiators from export on storage array %s (%s)",
                        storageSystem.getNativeGuid(), storage.toString()),
                storageSystem, method, rollbackMethodNullMethod(), waitFor, null);
    }

    /**
     * Generates a Workflow Step to change the path parameters of a Volume in a specific Export Group.
     *
     * @param workflow
     * @param wfGroupId - Workflow group for the Step
     * @param waitFor - Wait on this step/group to complete in the workflow before execution
     * @param storageURI - Storage system containing the volume
     * @param exportGroupURI - Export group that has exported the volume
     * @param volumeURI - Volume we are changing the path parameters for
     * @return stepId for the generated workflow Step
     * @throws ControllerException
     */
    public String generateExportChangePathParams(Workflow workflow, String wfGroupId, String waitFor,
            URI storageURI, URI exportGroupURI, URI volumeURI)
            throws ControllerException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storageURI);
        BlockObject volume = BlockObject.fetch(_dbClient, volumeURI);

        Workflow.Method method = ExportWorkflowEntryPoints.exportGroupChangePathParamsMethod(
                storageURI, exportGroupURI, volumeURI);
        return newWorkflowStep(workflow, wfGroupId,
                String.format("Updated Export PathParams on storage array %s (%s, args) for volume %s (%s)",
                        storageSystem.getNativeGuid(), storageURI, volume.getLabel(), volumeURI),
                storageSystem, method, null, waitFor, null);
    }

    /**
     * Generate a Workflow Step to change the Auto-tiering policy for the volumes
     * in a specific Export Mask.
     *
     * @param workflow the workflow
     * @param wfGroupId - Workflow group for the Step
     * @param waitFor - Wait on this step/group to complete in the workflow before execution
     * @param storageURI the storage system uri
     * @param exportMaskURI the export mask uri
     * @param volumeURIs the volume uris
     * @param exportGroupURI
     * @param newVpoolURI the new vpool uri
     * @return stepId for the generated workflow Step
     * @throws ControllerException the controller exception
     */
    public String generateExportChangePolicyAndLimits(Workflow workflow,
            String wfGroupId, String waitFor, URI storageURI,
            URI exportMaskURI, URI exportGroupURI, List<URI> volumeURIs,
            URI newVpoolURI, URI oldVpoolURI) throws ControllerException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storageURI);

        Workflow.Method method = ExportWorkflowEntryPoints.exportChangePolicyAndLimitsMethod(
                storageURI, exportMaskURI, exportGroupURI, volumeURIs, newVpoolURI, false);

        // same method is called as a roll back step by passing old vPool.
        // boolean is passed to differentiate it.
        Workflow.Method rollback = ExportWorkflowEntryPoints.exportChangePolicyAndLimitsMethod(
                storageURI, exportMaskURI, exportGroupURI, volumeURIs, oldVpoolURI, true);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Updating Auto-tiering Policy on storage array %s (%s, args) for volumes %s",
                        storageSystem.getNativeGuid(), storageURI, Joiner.on("\t").join(volumeURIs)),
                storageSystem, method, rollback, waitFor, null);
    }

    /**
     * Generate a Workflow Step to change the Auto-tiering policy for the volumes
     * in a specific Export Mask.
     *
     * @param workflow the workflow
     * @param wfGroupId - Workflow group for the Step
     * @param waitFor - Wait on this step/group to complete in the workflow before execution
     * @param storageURI the storage system uri
     * @param volumeURIs the volume uris
     * @param newVpoolURI the new vpool uri
     * @param oldVpoolURI the old vpool uri *
     * @return stepId for the generated workflow Step
     * @throws ControllerException the controller exception
     */
    public String generateChangeAutoTieringPolicy(Workflow workflow,
            String wfGroupId, String waitFor, URI storageURI,
            List<URI> volumeURIs, URI newVpoolURI, URI oldVpoolURI) throws ControllerException {
        DiscoveredSystemObject storageSystem = getStorageSystem(_dbClient, storageURI);

        Workflow.Method method = ExportWorkflowEntryPoints.changeAutoTieringPolicyMethod(
                storageURI, volumeURIs, newVpoolURI, false);

        // same method is called as a roll back step by passing old vPool.
        // boolean is passed to differentiate it.
        Workflow.Method rollback = ExportWorkflowEntryPoints.changeAutoTieringPolicyMethod(
                storageURI, volumeURIs, oldVpoolURI, true);

        return newWorkflowStep(workflow, wfGroupId,
                String.format("Updating Auto-tiering Policy on storage array %s (%s, args) for volumes %s",
                        storageSystem.getNativeGuid(), storageURI, Joiner.on("\t").join(volumeURIs)),
                storageSystem, method, rollback, waitFor, null);
    }

    /**
     * Wrapper for WorkflowService.getNewWorkflow
     *
     * @param name - Name of workflow
     * @param rollback - Boolean indicating whether rollback should be invoked or not.
     * @param opId - Operation id
     * @return Workflow object
     * @throws com.emc.storageos.workflow.WorkflowRestartedException
     */
    public Workflow newWorkflow(String name, boolean rollback, String opId)
            throws WorkflowRestartedException {
        return _workflowSvc.getNewWorkflow(_exportWfEntryPoints, name, rollback, opId);
    }

    /**
     * Wrapper for WorkflowService.createStep. The method expects that the method and
     * rollback (if specified) are in the ExportWorkflowEntryPoints class.
     *
     *
     * @param workflow
     *            - Workflow in which to add the step
     * @param groupId
     *            - String pointing to the group id of the step
     * @param description
     *            - String description of the step
     * @param storageSystem
     *            - StorageSystem object to which operation applies
     * @param method
     *            - Step method to be called
     * @param rollback
     *            - Step rollback method to be call (if any)
     * @param waitFor
     *            - String of groupId of step to wait for
     * @param stepId
     *            - step ID
     * @return String the stepId generated for the step.
     * @throws WorkflowException
     */
    public String newWorkflowStep(Workflow workflow,
            String groupId, String description,
            DiscoveredSystemObject storageSystem,
            Workflow.Method method, Workflow.Method rollback,
            String waitFor, String stepId)
            throws WorkflowException {
        if (groupId == null) {
            groupId = method.getClass().getSimpleName();
        }
        return workflow.createStep(groupId, description,
                waitFor, storageSystem.getId(),
                storageSystem.getSystemType(), ExportWorkflowEntryPoints.class, method,
                rollback, stepId);
    }

    /**
     * Returns a DiscoveredDataObject which can either point to a StorageSystem or
     * ProtectionSystem object.
     *
     * @param dbClient - DbClient object
     * @param storageURI - URI pointing to storage array object
     * @return DiscoveredDataObject
     */
    public static DiscoveredSystemObject getStorageSystem(DbClient dbClient,
            URI storageURI) {
        DiscoveredSystemObject ddo;
        if (URIUtil.isType(storageURI, ProtectionSystem.class)) {
            ddo = dbClient.queryObject(ProtectionSystem.class, storageURI);
        } else {
            ddo = dbClient.queryObject(StorageSystem.class, storageURI);
        }
        return ddo;
    }

    /**
     * Returns a list of Initiator URIs in the ExportGroup
     *
     * @param exportGroup
     * @return
     */
    private List<URI> getInitiators(ExportGroup exportGroup) {
        List<URI> initiatorURIs = new ArrayList<URI>();
        if (exportGroup.getInitiators() != null) {
            for (String initiator : exportGroup.getInitiators()) {
                try {
                    URI initiatorURI = new URI(initiator);
                    initiatorURIs.add(initiatorURI);
                } catch (URISyntaxException ex) {
                    _log.error("Bad URI syntax: " + initiator);
                }
            }
        }
        return initiatorURIs;
    }

    private boolean allCollectionsAreEmpty(Map<URI, Integer> addedBlockObjects, Map<URI, Integer> removedBlockObjects,
            List<URI> addedInitiators, List<URI> removedInitiators) {
        return (addedBlockObjects != null && addedBlockObjects.isEmpty() &&
                removedBlockObjects != null && removedBlockObjects.isEmpty() &&
                addedInitiators != null && addedInitiators.isEmpty() &&
                removedInitiators != null && removedInitiators.isEmpty());
    }

    /**
     * Creates a rollback workflow method that does nothing, but allows rollback
     * to continue to prior steps back up the workflow chain.
     *
     * @return A workflow method
     */
    private Workflow.Method rollbackMethodNullMethod() {
        return new Workflow.Method("rollbackMethodNull");
    }

    /**
     * Gets an instance of ProtectionExportController.
     * <p>
     * NOTE: This method currently only returns an instance of RPDeviceExportController. In the future, this will need to return other
     * protection export controllers if support is added.
     *
     * @return the ProtectionExportController
     */
    private ProtectionExportController getProtectionExportController() {
        return new RPDeviceExportController(_dbClient, this);
    }
}
