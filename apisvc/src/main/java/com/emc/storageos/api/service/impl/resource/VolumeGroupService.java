/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.api.service.impl.resource;

import static com.emc.storageos.api.mapper.DbObjectMapper.toNamedRelatedResource;
import static com.emc.storageos.api.mapper.TaskMapper.toTask;
import static com.emc.storageos.db.client.constraint.AlternateIdConstraint.Factory.getVolumesByAssociatedId;
import static com.emc.storageos.db.client.util.NullColumnValueGetter.isNullURI;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.mapper.BlockMapper;
import com.emc.storageos.api.mapper.DbObjectMapper;
import com.emc.storageos.api.mapper.TaskMapper;
import com.emc.storageos.api.service.impl.placement.PlacementManager;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyManager;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyUtils;
import com.emc.storageos.api.service.impl.resource.utils.BlockServiceUtils;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.PrefixConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.DataObject.Flag;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Task;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.VolumeGroup;
import com.emc.storageos.db.client.model.VolumeGroup.VolumeGroupRole;
import com.emc.storageos.db.client.model.util.TaskUtils;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.ResourceOnlyNameGenerator;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.SnapshotList;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.application.LabelSet;
import com.emc.storageos.model.application.VolumeGroupCreateParam;
import com.emc.storageos.model.application.VolumeGroupList;
import com.emc.storageos.model.application.VolumeGroupRestRep;
import com.emc.storageos.model.application.VolumeGroupUpdateParam;
import com.emc.storageos.model.block.BlockConsistencyGroupSnapshotCreate;
import com.emc.storageos.model.block.BlockSnapshotRestRep;
import com.emc.storageos.model.block.NamedVolumeGroupsList;
import com.emc.storageos.model.block.NamedVolumesList;
import com.emc.storageos.model.block.VolumeGroupFullCopyActivateParam;
import com.emc.storageos.model.block.VolumeGroupFullCopyCreateParam;
import com.emc.storageos.model.block.VolumeGroupFullCopyDetachParam;
import com.emc.storageos.model.block.VolumeGroupFullCopyRestoreParam;
import com.emc.storageos.model.block.VolumeGroupFullCopyResynchronizeParam;
import com.emc.storageos.model.block.VolumeGroupSnapshotCreateParam;
import com.emc.storageos.model.block.VolumeGroupSnapshotOperationParam;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.security.authorization.ACL;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.util.VPlexUtil;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

/**
 * APIs to view, create, modify and remove volume groups
 */

@Path("/volume-groups/block")
@DefaultPermissions(readRoles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, readAcls = { ACL.OWN, ACL.ALL }, writeRoles = {
        Role.TENANT_ADMIN }, writeAcls = { ACL.OWN, ACL.ALL })
public class VolumeGroupService extends TaskResourceService {
    private static final String VOLUME_GROUP_NAME = "name";
    private static final String VOLUME_GROUP_ROLES = "roles";
    private static final String EVENT_SERVICE_TYPE = "application";
    private static final Set<String> ALLOWED_SYSTEM_TYPES = new HashSet<String>(Arrays.asList(
            DiscoveredDataObject.Type.vnxblock.name(),
            DiscoveredDataObject.Type.vplex.name(),
            DiscoveredDataObject.Type.vmax.name(),
            DiscoveredDataObject.Type.xtremio.name(),
            DiscoveredDataObject.Type.scaleio.name(),
            DiscoveredDataObject.Type.rp.name(),
            DiscoveredDataObject.Type.srdf.name(),
            DiscoveredDataObject.Type.ibmxiv.name()));
    private static final Set<String> BLOCK_TYPES = new HashSet<String>(Arrays.asList(
            DiscoveredDataObject.Type.vnxblock.name(),
            DiscoveredDataObject.Type.vmax.name(),
            DiscoveredDataObject.Type.xtremio.name(),
            DiscoveredDataObject.Type.scaleio.name(),
            DiscoveredDataObject.Type.ibmxiv.name(),
            DiscoveredDataObject.Type.srdf.name()));
    private static final String BLOCK = "block";
    private static final String ID_FIELD = "id";
    private static final String NAME_FIELD = "name";
    private static final String VOLUMES_FIELD = "volumes";
    private static final String VOLUME_FIELD = "volume";
    private static final String LABEL_FIELD = "label";
    private static final String SNAPSHOT_ID_FIELD = "sid";
    private static final String SNAPSHOTS_FIELD = "snapshots";
    private static final String SNAPSHOT_FIELD = "snapshot";

    private static enum ReplicaTypeEnum {
        FULL_COPY("Full copy"),
        SNAPSHOT("Snapshot"),
        MIRROR("Continuous copy");

        private final String _name;
        private ReplicaTypeEnum(String name) {
            this._name = name;
        }

        @Override
        public String toString() {
            return _name;
        }
    };

    static final Logger log = LoggerFactory.getLogger(VolumeGroupService.class);

    // A reference to the placement manager.
    private PlacementManager _placementManager;

    // A reference to the block consistency group service.
    private BlockConsistencyGroupService _blockConsistencyGroupService;

    // Block service implementations
    private static Map<String, BlockServiceApi> _blockServiceApis;

    /**
     * Setter for the placement manager.
     *
     * @param placementManager A reference to the placement manager.
     */
    public void setPlacementManager(PlacementManager placementManager) {
        _placementManager = placementManager;
    }

    /**
     * Setter for the block consistency group service.
     * 
     * @param blockConsistencyGroupService A reference to the block consistency group service.
     */
    public void setBlockConsistencyGroupService(BlockConsistencyGroupService blockConsistencyGroupService) {
        _blockConsistencyGroupService = blockConsistencyGroupService;
    }

    public void setBlockServiceApis(final Map<String, BlockServiceApi> serviceInterfaces) {
        _blockServiceApis = serviceInterfaces;
    }

    private static BlockServiceApi getBlockServiceImpl(final String type) {
        return _blockServiceApis.get(type);
    }

    @Override
    protected DataObject queryResource(URI id) {
        ArgValidator.checkFieldUriType(id, VolumeGroup.class, "id");
        VolumeGroup volumeGroup = _permissionsHelper.getObjectById(id, VolumeGroup.class);
        ArgValidator.checkEntityNotNull(volumeGroup, id, isIdEmbeddedInURL(id));
        return volumeGroup;
    }

    @Override
    protected ResourceTypeEnum getResourceType() {
        return ResourceTypeEnum.VOLUME_GROUP;
    }

    @Override
    protected URI getTenantOwner(final URI id) {
        return null;
    }

    @Override
    public String getServiceType() {
        return EVENT_SERVICE_TYPE;
    }

    /**
     * Create a volume group
     *
     * @param param Parameters for creating a volume group
     * @return created volume group
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public VolumeGroupRestRep createVolumeGroup(VolumeGroupCreateParam param) {
        ArgValidator.checkFieldNotEmpty(param.getName(), VOLUME_GROUP_NAME);
        checkDuplicateLabel(VolumeGroup.class, param.getName());
        Set<String> roles = param.getRoles();
        ArgValidator.checkFieldNotEmpty(roles, VOLUME_GROUP_ROLES);
        for (String role : roles) {
            ArgValidator.checkFieldValueFromEnum(role, VOLUME_GROUP_ROLES, VolumeGroup.VolumeGroupRole.class);
        }
        VolumeGroup volumeGroup = new VolumeGroup();
        volumeGroup.setId(URIUtil.createId(VolumeGroup.class));
        volumeGroup.setLabel(param.getName());
        volumeGroup.setDescription(param.getDescription());
        volumeGroup.addRoles(param.getRoles());

        // add parent if specified
        String msg = setParent(volumeGroup, param.getParent());
        if (msg != null && !msg.isEmpty()) {
            throw APIException.badRequests.volumeGroupCantBeCreated(volumeGroup.getLabel(), msg);
        }

        _dbClient.createObject(volumeGroup);
        auditOp(OperationTypeEnum.CREATE_VOLUME_GROUP, true, null, volumeGroup.getId().toString(),
                volumeGroup.getLabel());
        return DbObjectMapper.map(volumeGroup);
    }

    /**
     * List a volume group
     *
     * @param id volume group Id
     * @return ApplicationRestRep
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}")
    public VolumeGroupRestRep getVolumeGroup(@PathParam("id") URI id) {
        ArgValidator.checkFieldUriType(id, VolumeGroup.class, "id");
        VolumeGroup volumeGroup = (VolumeGroup) queryResource(id);
        VolumeGroupRestRep resp = DbObjectMapper.map(volumeGroup);
        resp.setReplicationGroupNames(CopyVolumeGroupUtils.getReplicationGroupNames(volumeGroup, _dbClient));
        return resp;
    }

    /**
     * List volume groups.
     *
     * @return A reference to VolumeGroupList.
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public VolumeGroupList getVolumeGroups() {
        VolumeGroupList volumeGroupList = new VolumeGroupList();

        List<URI> ids = _dbClient.queryByType(VolumeGroup.class, true);
        Iterator<VolumeGroup> iter = _dbClient.queryIterativeObjects(VolumeGroup.class, ids);
        while (iter.hasNext()) {
            volumeGroupList.getVolumeGroups().add(toNamedRelatedResource(iter.next()));
        }
        return volumeGroupList;
    }

    /**
     * Get application volumes
     *
     * @param id Application Id
     * @return NamedVolumesList
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/volumes")
    public NamedVolumesList getVolumes(@PathParam("id") URI id) {
        ArgValidator.checkFieldUriType(id, VolumeGroup.class, "id");
        VolumeGroup volumeGroup = (VolumeGroup) queryResource(id);
        NamedVolumesList result = new NamedVolumesList();
        List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);
        for (Volume volume: volumes) {
            result.getVolumes().add(toNamedRelatedResource(volume));
        }
        return result;
    }

    /**
     * Get the list of child volume groups
     * 
     * @param id
     * @return
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/volume-groups")
    public NamedVolumeGroupsList getChildrenVolumeGroups(@PathParam("id") URI id) {
        ArgValidator.checkFieldUriType(id, VolumeGroup.class, "id");
        VolumeGroup volumeGroup = _dbClient.queryObject(VolumeGroup.class, id);
        NamedVolumeGroupsList result = new NamedVolumeGroupsList();
        List<VolumeGroup> volumeGroups = getVolumeGroupChildren(_dbClient, volumeGroup);
        for (VolumeGroup group : volumeGroups) {
            result.getVolumeGroups().add(toNamedRelatedResource(group));
        }
        return result;
    }

    /**
     * Delete the volume group.
     * When a volume group is deleted it will move to a "marked for deletion" state.
     *
     * @param id the URN of the volume group
     * @brief Deactivate application
     * @return No data returned in response body
     */
    @POST
    @Path("/{id}/deactivate")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public Response deactivateVolumeGroup(@PathParam("id") URI id) {
        ArgValidator.checkFieldUriType(id, VolumeGroup.class, "id");
        VolumeGroup volumeGroup = (VolumeGroup) queryResource(id);

        if (!ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup).isEmpty()) {
            // application could not be deleted if it has volumes
            throw APIException.badRequests.volumeGroupWithVolumesCantBeDeleted(volumeGroup.getLabel());
        }

        if (!getVolumeGroupChildren(_dbClient, volumeGroup).isEmpty()) {
            // application could not be deleted if it has child volume groups
            throw APIException.badRequests.volumeGroupWithChildrenCantBeDeleted(volumeGroup.getLabel());
        }

        // check for any other references to this volume group
        ArgValidator.checkReference(VolumeGroup.class, id, checkForDelete(volumeGroup));

        _dbClient.markForDeletion(volumeGroup);

        auditOp(OperationTypeEnum.DELETE_CONFIG, true, null, id.toString(),
                volumeGroup.getLabel());
        return Response.ok().build();
    }

    /**
     * update a volume group
     * @param id volume group id
     * @param param volume group update parameters
     * @return
     */
    @PUT
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.OWN, ACL.ALL })
    public TaskList updateVolumeGroup(@PathParam("id") final URI id,
            final VolumeGroupUpdateParam param) {
        ArgValidator.checkFieldUriType(id, VolumeGroup.class, "id");
        VolumeGroup volumeGroup = (VolumeGroup) queryResource(id);
        if (volumeGroup.getInactive()) {
            throw APIException.badRequests.volumeGroupCantBeUpdated(volumeGroup.getLabel(), "The Volume Group has been deleted");
        }
        checkForApplicationPendingTasks(volumeGroup);
        boolean isChanged = false;
        String vgName = param.getName();
        if (vgName != null && !vgName.isEmpty() && !vgName.equalsIgnoreCase(volumeGroup.getLabel())) {
            checkDuplicateLabel(VolumeGroup.class, vgName);
            volumeGroup.setLabel(vgName);
            isChanged = true;
        }
        String description = param.getDescription();
        if (description != null && !description.isEmpty()) {
            volumeGroup.setDescription(description);
            isChanged = true;
        }

        String parent = param.getParent();
        if (parent != null && !parent.isEmpty()) {
            String msg = setParent(volumeGroup, parent);
            if (msg != null && !msg.isEmpty()) {
                throw APIException.badRequests.volumeGroupCantBeUpdated(volumeGroup.getLabel(), msg);
            }
            isChanged = true;
        }

        if (isChanged) {
            _dbClient.updateObject(volumeGroup);
        }
        String taskId = UUID.randomUUID().toString();
        TaskList taskList = new TaskList();
        Operation op = null;
        if (!param.hasEitherAddOrRemoveVolumes()) {
            op = new Operation();
            op.setResourceType(ResourceOperationTypeEnum.UPDATE_VOLUME_GROUP);
            op.ready();
            volumeGroup.getOpStatus().createTaskStatus(taskId, op);
            _dbClient.updateObject(volumeGroup);
            TaskResourceRep task = toTask(volumeGroup, taskId, op);
            taskList.getTaskList().add(task);
            return taskList;
        }

        List<VolumeGroupUtils> utils = getVolumeGroupUtils(volumeGroup);
        for (VolumeGroupUtils util : utils) {
            util.validateUpdateVolumesInVolumeGroup(_dbClient, param, volumeGroup);
        }
        for (VolumeGroupUtils util : utils) {
            util.updateVolumesInVolumeGroup(_dbClient, param, volumeGroup, taskId, taskList);
        }
        auditOp(OperationTypeEnum.UPDATE_VOLUME_GROUP, true, AuditLogManager.AUDITOP_BEGIN, volumeGroup.getId().toString(),
                volumeGroup.getLabel());
        return taskList;
    }

    /**
     * Creates a volume group full copy
     * - Creates full copy for all the array replication groups within this Application.
     * - If partial flag is specified, it creates full copy only for set of array replication groups.
     * A Volume from each array replication group can be provided to indicate which array replication
     * groups are required to take full copy.
     * 
     * @prereq none
     *
     * @param volumeGroupId the URI of the Volume Group
     *            - Volume group URI
     * @param param VolumeGroupFullCopyCreateParam
     *
     * @brief Create volume group fullcopy
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN }, acls = { ACL.ANY })
    public TaskList createVolumeGroupFullCopy(@PathParam("id") final URI volumeGroupId,
            VolumeGroupFullCopyCreateParam param) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        ArgValidator.checkEntityNotNull(volumeGroup, volumeGroupId, isIdEmbeddedInURL(volumeGroupId));

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.FULL_COPY);

        TaskList taskList = new TaskList();

        // get all volumes
        List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);
        // validate that there should be some volumes in VolumeGroup
        if (volumes.isEmpty()) {
            throw APIException.badRequests.replicaOperationNotAllowedOnEmptyVolumeGroup(volumeGroup.getLabel(), ReplicaTypeEnum.FULL_COPY.toString());
        }

        List<VolumeGroupUtils> utils = getVolumeGroupUtils(volumeGroup);
        for (VolumeGroupUtils util : utils) {
            // TODO XtremIO array does not support clone.
            // If volume group has mix of storage arrays, entire Clone creation workflow will fail (rolled back)
            // In such cases and not to have partial clone, we may need to restrict user at API level.
            // may be use Copy-VolumeGroupUtils to validate such things.
        }

        if (param.getPartial()) {
            log.info("Full Copy requested for subset of array groups in Application.");

            // validate that at least one volume URI is provided
            ArgValidator.checkFieldNotEmpty(param.getVolumes(), "volumes");

            // validate that provided volumes
            Set<String> arrayGroupNames = new HashSet<String>();
            List<Volume> volumesInRequest = new ArrayList<Volume>();
            for (URI volumeURI : param.getVolumes()) {
                ArgValidator.checkFieldUriType(volumeURI, Volume.class, "volume");
                // Get the Volume.
                Volume volume = (Volume) BlockFullCopyUtils.queryFullCopyResource(volumeURI,
                        uriInfo, true, _dbClient);
                ArgValidator.checkEntityNotNull(volume, volumeURI, isIdEmbeddedInURL(volumeURI));

                String arrayGroupName = volume.getReplicationGroupInstance();
                if (arrayGroupName == null && volume.isVPlexVolume(_dbClient)) {
                    // get backend source volume to get RG name
                    Volume backedVol = VPlexUtil.getVPLEXBackendVolume(volume, true, _dbClient);
                    if (backedVol != null) {
                        arrayGroupName = backedVol.getReplicationGroupInstance();
                    }
                }

                // skip repeated array groups
                if (arrayGroupNames.contains(arrayGroupName)) {
                    log.info("Skipping repetitive request for Volume array group {}. Volume: {}",
                            arrayGroupName, volume.getLabel());
                    continue;
                }
                arrayGroupNames.add(arrayGroupName);

                volumesInRequest.add(volume);
            }

            // send create request after validating all volumes
            String name = param.getName();
            for (Volume volume : volumesInRequest) {
                // set Flag in Volume so that we will know about partial request during processing.
                volume.addInternalFlags(Flag.VOLUME_GROUP_PARTIAL_REQUEST);
                _dbClient.updateObject(volume);

                // Create full copy. Note that it will take into account the
                // fact that the volume is in a ReplicationGroup
                // and full copies will be created for all volumes in that ReplicationGroup.

                // In case of partial request, Tasks will be generated for each Array group
                // and they cannot be monitored together.

                // append replication group name to requested full copy name
                // to make the requested name unique across array replication groups
                param.setName(name + "_" + volume.getReplicationGroupInstance());
                try {
                    taskList.getTaskList().addAll(getFullCopyManager().createFullCopy(volume.getId(), param).getTaskList());
                } catch (Exception e) {
                    volume.clearInternalFlags(Flag.VOLUME_GROUP_PARTIAL_REQUEST);
                    _dbClient.updateObject(volume);
                    throw e;
                }
            }
        } else {
            log.info("Full Copy requested for entire Application");
            auditOp(OperationTypeEnum.CREATE_VOLUME_GROUP_FULL_COPY, true, AuditLogManager.AUDITOP_BEGIN, volumeGroup.getId().toString(),
                    param.getName(), param.getCount());

            // Full copy will be created for all volumes in Application
            taskList = getFullCopyManager().createFullCopy(volumes.get(0).getId(), param);
        }

        return taskList;
    }

    /**
     * List full copies for a volume group
     *
     * @prereq none
     *
     * @param volumeGroupId The URI of the volume group.
     *
     * @brief List full copies for a volume group
     *
     * @return The list of full copies for the volume group
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public NamedVolumesList getVolumeGroupFullCopies(@PathParam("id") final URI volumeGroupId) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.FULL_COPY);

        // get all volumes
        List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // Cycle over the volumes in the volume group and
        // get the full copies for each volume in the group.
        NamedVolumesList fullCopyList = new NamedVolumesList();
        for (Volume volume : volumes) {
            NamedVolumesList volumeFullCopies = getFullCopyManager().getFullCopiesForSource(volume.getId());
            fullCopyList.getVolumes().addAll(volumeFullCopies.getVolumes());
        }

        return fullCopyList;
    }

    /**
     * Get the specified volume group full copy.
     *
     * @prereq none
     *
     * @param volumeGroupId The URI of the volume group.
     * @param fullCopyURI The URI of the full copy.
     *
     * @brief Get the specified volume group full copy.
     *
     * @return The full copy volume.
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/{fcid}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public VolumeRestRep getVolumeGroupFullCopy(@PathParam("id") final URI volumeGroupId,
            @PathParam("fcid") URI fullCopyURI) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.FULL_COPY);

        // get all volumes
        List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        Volume fullCopyVolume = (Volume) BlockFullCopyUtils.queryFullCopyResource(
                fullCopyURI, uriInfo, false, _dbClient);
        verifyReplicaForCopyRequest(fullCopyVolume, volumes);

        // Get and return the full copy.
        return getFullCopyManager().getFullCopy(fullCopyURI);
    }

    /**
     * Activate the specified Volume group full copy.
     * - Activates full copy for all the array replication groups within this Application.
     * - If partial flag is specified, it activates full copy only for set of array replication groups.
     * A Full Copy from each array replication group can be provided to indicate which array replication
     * groups's full copies needs to be activated.
     *
     * @prereq Create Volume group full copy as inactive.
     *
     * @param volumeGroupId The URI of the Volume group.
     * @param fullCopyURI The URI of the full copy.
     *
     * @brief Activate Volume group full copy.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/activate")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList activateVolumeGroupFullCopy(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupFullCopyActivateParam param) {

        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        TaskList taskList = new TaskList();

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.FULL_COPY);

        // validate that at least one full copy URI is provided
        ArgValidator.checkFieldNotEmpty(param.getFullCopies(), "fullCopies");

        // get full copy manager
        BlockFullCopyManager fullCopyManager = getFullCopyManager();

        // get all volumes for volume group
        List<Volume> volumeGroupVolumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // validate the requested full copies
        List<Volume> fullCopyVolumesInRequest = validateFullCopiesInRequest(param.getFullCopies(), volumeGroupVolumes);

        /**
         * 1. VolumeGroupService Clone API accepts a Clone URI (to identify clone set and RG)
         * - then get All full copies belonging to same full copy set
         * - get full copy set name from the requested full copy
         * 2. If partial, there will be a List of Clone URIs (one from each RG)
         * 3. Group the full copies by Replication Group(RG)
         * 4. For each RG, invoke the ConsistencyGroup full copy API (CG uri, clone uri)
         * - a. Skip the CG/RG calls when thrown error and continue with other entries; create 'ERROR' Task for this call
         * - b. Finally return the Task List (RG tasks may finish at different times as they are different calls)
         */
        if (!param.getPartial()) {
            Volume fullCopy = fullCopyVolumesInRequest.get(0);
            log.info("Full Copy operation requested for entire Application, Considering full copy {} in request.",
                    fullCopy.getLabel());
            fullCopyVolumesInRequest.clear();
            fullCopyVolumesInRequest.addAll(fullCopyManager.getFullCopiesForSet(fullCopy, volumeGroupVolumes));

            // make sure we don't pick up full copies of volumes not belonging to this application
            for (Volume fullCopyVolume : fullCopyVolumesInRequest) {
                verifyReplicaForCopyRequest(fullCopyVolume, volumeGroupVolumes);
            }
        } else {
            log.info("Full Copy operation requested for subset of array replication groups in Application.");
        }

        Map<String, Volume> repGroupToFullCopyMap = groupFullCopiesByReplicationGroup(fullCopyVolumesInRequest);
        for (Map.Entry<String, Volume> entry : repGroupToFullCopyMap.entrySet()) {
            String replicationGroup = entry.getKey();
            Volume fullCopy = entry.getValue();
            log.info("Processing Array Replication Group {}, Full Copy {}", replicationGroup, fullCopy.getLabel());
            try {
                // get CG URI
                URI cgURI = getConsistencyGroupForFullCopy(fullCopy);

                // Activate the full copy. Note that it will take into account the
                // fact that the volume is in a ReplicationGroup
                // and all volumes in that ReplicationGroup will be activated.
                taskList.getTaskList().addAll(
                        _blockConsistencyGroupService.activateConsistencyGroupFullCopy(cgURI, fullCopy.getId())
                                .getTaskList());
            } catch (InternalException | APIException e) {
                String errMsg = String.format("Error activating Array Replication Group %s, Full Copy %s",
                        replicationGroup, fullCopy.getLabel());
                log.error(errMsg, e);
                TaskResourceRep task = createFailedTaskOnVolume(fullCopy,
                        ResourceOperationTypeEnum.ACTIVATE_VOLUME_FULL_COPY, e);
                taskList.addTask(task);
            }
        }

        if (!param.getPartial()) {
            auditOp(OperationTypeEnum.ACTIVATE_VOLUME_GROUP_FULL_COPY, true, AuditLogManager.AUDITOP_BEGIN,
                    volumeGroup.getId().toString(), fullCopyVolumesInRequest.get(0).getLabel());
        }

        return taskList;
    }

    /**
     * Detach the specified Volume group full copy.
     * - Detaches full copy for all the array replication groups within this Application.
     * - If partial flag is specified, it detaches full copy only for set of array replication groups.
     * A Full Copy from each array replication group can be provided to indicate which array replication
     * groups's full copies needs to be detached.
     * 
     * @prereq Create Volume group full copy as active.
     *
     * @param volumeGroupId The URI of the Volume group.
     * @param fullCopyURI The URI of the full copy.
     *
     * @brief Detach Volume group full copy.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/detach")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList detachVolumeGroupFullCopy(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupFullCopyDetachParam param) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        TaskList taskList = new TaskList();

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.FULL_COPY);

        // validate that at least one full copy URI is provided
        ArgValidator.checkFieldNotEmpty(param.getFullCopies(), "volumes");

        // get full copy manager
        BlockFullCopyManager fullCopyManager = getFullCopyManager();

        // get all volumes for volume group
        List<Volume> volumeGroupVolumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // validate the requested full copies
        List<Volume> fullCopyVolumesInRequest = validateFullCopiesInRequest(param.getFullCopies(), volumeGroupVolumes);

        /**
         * 1. VolumeGroupService Clone API accepts a Clone URI (to identify clone set and RG)
         * - then get All full copies belonging to same full copy set
         * - get full copy set name from the requested full copy
         * 2. If partial, there will be a List of Clone URIs (one from each RG)
         * 3. Group the full copies by Replication Group(RG)
         * 4. For each RG, invoke the ConsistencyGroup full copy API (CG uri, clone uri)
         * - a. Skip the CG/RG calls when thrown error and continue with other entries; create 'ERROR' Task for this call
         * - b. Finally return the Task List (RG tasks may finish at different times as they are different calls)
         */
        if (!param.getPartial()) {
            Volume fullCopy = fullCopyVolumesInRequest.get(0);
            log.info("Full Copy operation requested for entire Application, Considering full copy {} in request.",
                    fullCopy.getLabel());
            fullCopyVolumesInRequest.clear();
            fullCopyVolumesInRequest.addAll(fullCopyManager.getFullCopiesForSet(fullCopy, volumeGroupVolumes));

            // make sure we don't pick up full copies of volumes not belonging to this application
            for (Volume fullCopyVolume : fullCopyVolumesInRequest) {
                verifyReplicaForCopyRequest(fullCopyVolume, volumeGroupVolumes);
            }
        } else {
            log.info("Full Copy operation requested for subset of array replication groups in Application.");
        }

        Map<String, Volume> repGroupToFullCopyMap = groupFullCopiesByReplicationGroup(fullCopyVolumesInRequest);
        for (Map.Entry<String, Volume> entry : repGroupToFullCopyMap.entrySet()) {
            String replicationGroup = entry.getKey();
            Volume fullCopy = entry.getValue();
            log.info("Processing Array Replication Group {}, Full Copy {}", replicationGroup, fullCopy.getLabel());
            try {
                // get CG URI
                URI cgURI = getConsistencyGroupForFullCopy(fullCopy);

                // Detach the full copy. Note that it will take into account the
                // fact that the volume is in a ReplicationGroup
                // and all volumes in that ReplicationGroup will be detached.
                taskList.getTaskList().addAll(
                        _blockConsistencyGroupService.detachConsistencyGroupFullCopy(cgURI, fullCopy.getId())
                                .getTaskList());
            } catch (InternalException | APIException e) {
                String errMsg = String.format("Error detaching Array Replication Group %s, Full Copy %s",
                        replicationGroup, fullCopy.getLabel());
                log.error(errMsg, e);
                TaskResourceRep task = createFailedTaskOnVolume(fullCopy,
                        ResourceOperationTypeEnum.DETACH_VOLUME_FULL_COPY, e);
                taskList.addTask(task);
            }
        }

        if (!param.getPartial()) {
            auditOp(OperationTypeEnum.DETACH_VOLUME_GROUP_FULL_COPY, true, AuditLogManager.AUDITOP_BEGIN,
                    volumeGroup.getId().toString(), fullCopyVolumesInRequest.get(0).getLabel());
        }

        return taskList;
    }

    /**
     * Restore the specified Volume group full copy.
     * - Restores full copy for all the array replication groups within this Application.
     * - If partial flag is specified, it restores full copy only for set of array replication groups.
     * A Full Copy from each array replication group can be provided to indicate which array replication
     * groups's full copies needs to be restored.
     *
     * @prereq Create Volume group full copy as active.
     *
     * @param volumeGroupId The URI of the Volume group.
     * @param fullCopyURI The URI of the full copy.
     *
     * @brief Restore Volume group full copy.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/restore")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList restoreVolumeGroupFullCopy(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupFullCopyRestoreParam param) {

        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        TaskList taskList = new TaskList();

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.FULL_COPY);

        // validate that at least one full copy URI is provided
        ArgValidator.checkFieldNotEmpty(param.getFullCopies(), "volumes");

        // get full copy manager
        BlockFullCopyManager fullCopyManager = getFullCopyManager();

        // get all volumes for volume group
        List<Volume> volumeGroupVolumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // validate the requested full copies
        List<Volume> fullCopyVolumesInRequest = validateFullCopiesInRequest(param.getFullCopies(), volumeGroupVolumes);

        /**
         * 1. VolumeGroupService Clone API accepts a Clone URI (to identify clone set and RG)
         * - then get All full copies belonging to same full copy set
         * - get full copy set name from the requested full copy
         * 2. If partial, there will be a List of Clone URIs (one from each RG)
         * 3. Group the full copies by Replication Group(RG)
         * 4. For each RG, invoke the ConsistencyGroup full copy API (CG uri, clone uri)
         * - a. Skip the CG/RG calls when thrown error and continue with other entries; create 'ERROR' Task for this call
         * - b. Finally return the Task List (RG tasks may finish at different times as they are different calls)
         */
        if (!param.getPartial()) {
            Volume fullCopy = fullCopyVolumesInRequest.get(0);
            log.info("Full Copy operation requested for entire Application, Considering full copy {} in request.",
                    fullCopy.getLabel());
            fullCopyVolumesInRequest.clear();
            fullCopyVolumesInRequest.addAll(fullCopyManager.getFullCopiesForSet(fullCopy, volumeGroupVolumes));

            // make sure we don't pick up full copies of volumes not belonging to this application
            for (Volume fullCopyVolume : fullCopyVolumesInRequest) {
                verifyReplicaForCopyRequest(fullCopyVolume, volumeGroupVolumes);
            }
        } else {
            log.info("Full Copy operation requested for subset of array replication groups in Application.");
        }

        Map<String, Volume> repGroupToFullCopyMap = groupFullCopiesByReplicationGroup(fullCopyVolumesInRequest);
        for (Map.Entry<String, Volume> entry : repGroupToFullCopyMap.entrySet()) {
            String replicationGroup = entry.getKey();
            Volume fullCopy = entry.getValue();
            log.info("Processing Array Replication Group {}, Full Copy {}", replicationGroup, fullCopy.getLabel());
            try {
                // get CG URI
                URI cgURI = getConsistencyGroupForFullCopy(fullCopy);

                // Restore the full copy. Note that it will take into account the
                // fact that the volume is in a ReplicationGroup
                // and all volumes in that ReplicationGroup will be restored.
                taskList.getTaskList().addAll(
                        _blockConsistencyGroupService.restoreConsistencyGroupFullCopy(cgURI, fullCopy.getId())
                                .getTaskList());
            } catch (InternalException | APIException e) {
                String errMsg = String.format("Error restoring Array Replication Group %s, Full Copy %s",
                        replicationGroup, fullCopy.getLabel());
                log.error(errMsg, e);
                TaskResourceRep task = createFailedTaskOnVolume(fullCopy,
                        ResourceOperationTypeEnum.RESTORE_VOLUME_FULL_COPY, e);
                taskList.addTask(task);
            }
        }

        if (!param.getPartial()) {
            auditOp(OperationTypeEnum.RESTORE_VOLUME_GROUP_FULL_COPY, true, AuditLogManager.AUDITOP_BEGIN,
                    volumeGroup.getId().toString(), fullCopyVolumesInRequest.get(0).getLabel());
        }

        return taskList;
    }

    /**
     * Resynchronize the specified Volume group full copy.
     * - Resynchronizes full copy for all the array replication groups within this Application.
     * - If partial flag is specified, it resynchronizes full copy only for set of array replication groups.
     * A Full Copy from each array replication group can be provided to indicate which array replication
     * groups's full copies needs to be resynchronized.
     *
     * @prereq Create Volume group full copy as active.
     *
     * @param volumeGroupId The URI of the Volume group.
     * @param fullCopyURI The URI of the full copy.
     *
     * @brief Resynchronize Volume group full copy.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/full-copies/resynchronize")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList resynchronizeVolumeGroupFullCopy(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupFullCopyResynchronizeParam param) {

        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        TaskList taskList = new TaskList();

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.FULL_COPY);

        // validate that at least one full copy URI is provided
        ArgValidator.checkFieldNotEmpty(param.getFullCopies(), "volumes");

        // get full copy manager
        BlockFullCopyManager fullCopyManager = getFullCopyManager();

        // get all volumes for volume group
        List<Volume> volumeGroupVolumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // validate the requested full copies
        List<Volume> fullCopyVolumesInRequest = validateFullCopiesInRequest(param.getFullCopies(), volumeGroupVolumes);

        /**
         * 1. VolumeGroupService Clone API accepts a Clone URI (to identify clone set and RG)
         * - then get All full copies belonging to same full copy set
         * - get full copy set name from the requested full copy
         * 2. If partial, there will be a List of Clone URIs (one from each RG)
         * 3. Group the full copies by Replication Group(RG)
         * 4. For each RG, invoke the ConsistencyGroup full copy API (CG uri, clone uri)
         * - a. Skip the CG/RG calls when thrown error and continue with other entries; create 'ERROR' Task for this call
         * - b. Finally return the Task List (RG tasks may finish at different times as they are different calls)
         */
        if (!param.getPartial()) {
            Volume fullCopy = fullCopyVolumesInRequest.get(0);
            log.info("Full Copy operation requested for entire Application, Considering full copy {} in request.",
                    fullCopy.getLabel());
            fullCopyVolumesInRequest.clear();
            fullCopyVolumesInRequest.addAll(fullCopyManager.getFullCopiesForSet(fullCopy, volumeGroupVolumes));

            // make sure we don't pick up full copies of volumes not belonging to this application
            for (Volume fullCopyVolume : fullCopyVolumesInRequest) {
                verifyReplicaForCopyRequest(fullCopyVolume, volumeGroupVolumes);
            }
        } else {
            log.info("Full Copy operation requested for subset of array replication groups in Application.");
        }

        Map<String, Volume> repGroupToFullCopyMap = groupFullCopiesByReplicationGroup(fullCopyVolumesInRequest);
        for (Map.Entry<String, Volume> entry : repGroupToFullCopyMap.entrySet()) {
            String replicationGroup = entry.getKey();
            Volume fullCopy = entry.getValue();
            log.info("Processing Array Replication Group {}, Full Copy {}", replicationGroup, fullCopy.getLabel());
            try {
                // get CG URI
                URI cgURI = getConsistencyGroupForFullCopy(fullCopy);

                // Resynchronize the full copy. Note that it will take into account the
                // fact that the volume is in a ReplicationGroup
                // and all volumes in that ReplicationGroup will be resynchronized.
                taskList.getTaskList().addAll(
                        _blockConsistencyGroupService.resynchronizeConsistencyGroupFullCopy(cgURI, fullCopy.getId())
                                .getTaskList());
            } catch (InternalException | APIException e) {
                String errMsg = String.format("Error resynchronizing Array Replication Group %s, Full Copy %s",
                        replicationGroup, fullCopy.getLabel());
                log.error(errMsg, e);
                TaskResourceRep task = createFailedTaskOnVolume(fullCopy,
                        ResourceOperationTypeEnum.RESYNCHRONIZE_VOLUME_FULL_COPY, e);
                taskList.addTask(task);
            }
        }

        if (!param.getPartial()) {
            auditOp(OperationTypeEnum.RESYNCHRONIZE_VOLUME_GROUP_FULL_COPY, true, AuditLogManager.AUDITOP_BEGIN,
                    volumeGroup.getId().toString(), fullCopyVolumesInRequest.get(0).getLabel());
        }

        return taskList;
    }

    /**
     * Validate full copies for partial request.
     *
     * @param fullCopyURIsInRequest the full copies in request
     * @param volumeGroupVolumes the volume group volumes
     * @return the full copy objects
     */
    private List<Volume> validateFullCopiesInRequest(final List<URI> fullCopyURIsInRequest, List<Volume> volumeGroupVolumes) {
        List<String> arrayGroupNames = new ArrayList<String>();
        List<Volume> fullCopyVolumesInRequest = new ArrayList<Volume>();
        for (URI fullCopyURI : fullCopyURIsInRequest) {
            ArgValidator.checkFieldUriType(fullCopyURI, Volume.class, "volume");
            // Get the full copy.
            Volume fullCopyVolume = (Volume) BlockFullCopyUtils.queryFullCopyResource(
                    fullCopyURI, uriInfo, false, _dbClient);

            // skip repeated array groups
            if (arrayGroupNames.contains(fullCopyVolume.getReplicationGroupInstance())) {
                log.info("Skipping repetitive request for Full Copy array group {}. Full Copy: {}",
                        fullCopyVolume.getReplicationGroupInstance(), fullCopyVolume.getLabel());
                continue;
            }
            arrayGroupNames.add(fullCopyVolume.getReplicationGroupInstance());

            verifyReplicaForCopyRequest(fullCopyVolume, volumeGroupVolumes);

            fullCopyVolumesInRequest.add(fullCopyVolume);
        }
        return fullCopyVolumesInRequest;
    }

    /**
     * Returns a map of replication group name to full copy.
     *
     * @param fullCopies the full copies
     * @return the map of replication group to full copy
     */
    private Map<String, Volume> groupFullCopiesByReplicationGroup(List<Volume> fullCopies) {
        Map<String, Volume> repGroupToFullCopyMap = new HashMap<String, Volume>();
        for (Volume fullCopy : fullCopies) {
            String repGroupName = fullCopy.getReplicationGroupInstance();
            if (repGroupName == null && fullCopy.isVPlexVolume(_dbClient)) {
                // get backend source volume to get RG name
                Volume backedVol = VPlexUtil.getVPLEXBackendVolume(fullCopy, true, _dbClient);
                if (backedVol != null) {
                    repGroupName = backedVol.getReplicationGroupInstance();
                }
            }
            // duplicate group names will be overwritten
            repGroupToFullCopyMap.put(repGroupName, fullCopy);
        }
        // TODO how do we group full copies when volumes of different RGs with fullCopies are added to Application?
        return repGroupToFullCopyMap;
    }

    /**
     * Gets the consistency group for full copy.
     *
     * @param fullCopy the full copy
     * @return the consistency group for full copy
     */
    private URI getConsistencyGroupForFullCopy(Volume fullCopy) {
        if (NullColumnValueGetter.isNullURI(fullCopy.getAssociatedSourceVolume())) {
            // Full Copy may already be Detached
            throw APIException.badRequests
                    .replicaOperationNotAllowedNotAReplica(ReplicaTypeEnum.FULL_COPY.toString(), fullCopy.getLabel());
        }
        Volume srcVolume = _dbClient.queryObject(Volume.class, fullCopy.getAssociatedSourceVolume());
        return srcVolume != null ? srcVolume.getConsistencyGroup() : null;
    }

    /**
     * Creates a Task on given volume with Error state
     *
     * @param opr the opr
     * @param volume the volume
     * @param sc the sc
     * @return the failed task for volume
     */
    private TaskResourceRep createFailedTaskOnVolume(Volume volume, ResourceOperationTypeEnum opr, ServiceCoded sc) {
        String taskId = UUID.randomUUID().toString();
        Operation op = new Operation();
        op.setResourceType(opr);
        _dbClient.createTaskOpStatus(Volume.class, volume.getId(), taskId, op);

        // TODO check for creating Op with error
        volume = _dbClient.queryObject(Volume.class, volume.getId());
        op = volume.getOpStatus().get(taskId);
        op.error(sc);
        volume.getOpStatus().updateTaskStatus(taskId, op);
        _dbClient.updateObject(volume);
        return TaskMapper.toTask(volume, taskId, op);
    }
    /**
     * allow replica operation only for COPY type VolumeGroup.
     * 
     * @param volumeGroup
     * @param replicaType
     */
    private void validateCopyOperationForVolumeGroup(VolumeGroup volumeGroup, ReplicaTypeEnum replicaType) {
        if (!volumeGroup.getRoles().contains(VolumeGroupRole.COPY.name())) {
            throw APIException.badRequests.replicaOperationNotAllowedForNonCopyTypeVolumeGroup(volumeGroup.getLabel(), replicaType.toString());
        }
    }

    /**
     * Creates and returns an instance of the block full copy manager to handle
     * a full copy request.
     *
     * @return BlockFullCopyManager
     */
    private BlockFullCopyManager getFullCopyManager() {
        BlockFullCopyManager fcManager = new BlockFullCopyManager(_dbClient,
                _permissionsHelper, _auditMgr, _coordinator, _placementManager, sc, uriInfo,
                _request, null);
        return fcManager;
    }

    /**
     * Verifies that the passed replica URI and ensure that it
     * represents a replica for a volume in the passed list of
     * volumes, which are the volumes for a specific volume
     * group.
     *
     * @param replica the replica (Clone/Snapshot/Mirror)
     * @param volumeGroupVolumes the volume group's volumes
     * @return The URI of the replica's source.
     */
    private URI verifyReplicaForCopyRequest(BlockObject replica, List<Volume> volumeGroupVolumes) {
        URI sourceURI = null;
        ReplicaTypeEnum replicaType = null;
        if (replica instanceof BlockSnapshot) {
            sourceURI = ((BlockSnapshot) replica).getParent().getURI();
            replicaType = ReplicaTypeEnum.SNAPSHOT;
        } else if (replica instanceof BlockMirror) {
            sourceURI = ((BlockMirror) replica).getSource().getURI();
            replicaType = ReplicaTypeEnum.MIRROR;
        } else if (replica instanceof Volume) {
            sourceURI = ((Volume) replica).getAssociatedSourceVolume();
            replicaType = ReplicaTypeEnum.FULL_COPY;
        }

        if (NullColumnValueGetter.isNullURI(sourceURI)) {
            throw APIException.badRequests
                    .replicaOperationNotAllowedNotAReplica(replicaType.toString(), replica.getLabel());
        }

        // Verify the source is in the volume group.
        boolean sourceInVolumeGroup = false;
        for (Volume volume : volumeGroupVolumes) {
            if (volume.getId().equals(sourceURI)) {
                sourceInVolumeGroup = true;
                break;
            }
        }
        if (!sourceInVolumeGroup) {
            throw APIException.badRequests
                    .replicaOperationNotAllowedSourceNotInVolumeGroup(replicaType.toString(), replica.getLabel());
        }
        return sourceURI;
    }

    /*
     * get all snapsetLabels associated with the volume group
     */
    private LabelSet getVolumeGroupSnapsetLabels(VolumeGroup volumeGroup) {
        // get all volumes
        List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // get the snapshots for each volume in the group
        LabelSet labelSet = new LabelSet();
        Set<String> labels = labelSet.getLabels();

        for (Volume volume : volumes) {
            URIQueryResultList snapshotURIs = new URIQueryResultList();
            _dbClient.queryByConstraint(ContainmentConstraint.Factory.getVolumeSnapshotConstraint(
                    volume.getId()), snapshotURIs);
            if (!snapshotURIs.iterator().hasNext()) {
                continue;
            }

            List<BlockSnapshot> snapshots = _dbClient.queryObject(BlockSnapshot.class, snapshotURIs);
            for (BlockSnapshot snapshot : snapshots) {
                if (snapshot != null && !snapshot.getInactive()) {
                    String snapsetLabel = snapshot.getSnapsetLabel();
                    if (NullColumnValueGetter.isNotNullValue(snapsetLabel)) {
                        labels.add(snapsetLabel);
                    }
                }
            }
        }

        return labelSet;
    }

    /**
     * Creates a volume group snapshot
     * - Creates snapshot for all the array replication groups within this Application.
     * - If partial flag is specified, it creates snapshot only for set of array replication groups.
     * A Volume from each array replication group can be provided to indicate which array replication
     * groups are required to take snapshot.
     *
     * @prereq none
     *
     * @param volumeGroupId the URI of the Volume Group
     *            - Volume group URI
     * @param param VolumeGroupSnapshotCreateParam
     *
     * @brief Create volume group snapshot
     * @return TaskList
     */
    /**
     * @param volumeGroupId
     * @param param
     * @return
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots")
    @CheckPermission(roles = { Role.SYSTEM_ADMIN }, acls = { ACL.ANY })
    public TaskList createVolumeGroupSnapshot(@PathParam("id") final URI volumeGroupId,
            VolumeGroupSnapshotCreateParam param) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, ID_FIELD);
        // Query volume group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        ArgValidator.checkEntityNotNull(volumeGroup, volumeGroupId, isIdEmbeddedInURL(volumeGroupId));

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.SNAPSHOT);

        // validate name
        String name = param.getName();
        ArgValidator.checkFieldNotEmpty(name, NAME_FIELD);

        // snapsetLabel is normalized in RP, do it here too to avoid potential mismatch
        name = ResourceOnlyNameGenerator.removeSpecialCharsForName(name, SmisConstants.MAX_SNAPSHOT_NAME_LENGTH);
        if (StringUtils.isEmpty(name)) {
            // original name has special chars only
            throw APIException.badRequests.invalidReplicaSetLabel(param.getName(),
                    ReplicaTypeEnum.SNAPSHOT.toString());
        }

        // check name provided is not duplicate
        LabelSet labelSet = getVolumeGroupSnapsetLabels(volumeGroup);
        if (labelSet.getLabels().contains(name)) {
            // duplicate name
            throw APIException.badRequests.duplicateReplicaSetLabel(param.getName(),
                    ReplicaTypeEnum.SNAPSHOT.toString());
        }

        // volumes to be processed
        List<Volume> volumes = null;

        if (param.getPartial()) {
            log.info("Snapshot requested for subset of array groups in Application.");

            // validate that at least one volume URI is provided
            ArgValidator.checkFieldNotEmpty(param.getVolumes(), VOLUMES_FIELD);

            volumes = new ArrayList<Volume>();
            // validate that provided volumes
            for (URI volumeURI : param.getVolumes()) {
                ArgValidator.checkFieldUriType(volumeURI, Volume.class, VOLUME_FIELD);
                // Get the volume
                Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
                ArgValidator.checkEntityNotNull(volume, volumeURI, isIdEmbeddedInURL(volumeURI));

                // validate that provided volume is part of Volume Group
                if (volume.getVolumeGroupIds().contains(volumeGroupId.toString())) {
                    throw APIException.badRequests
                            .replicaOperationNotAllowedVolumeNotInVolumeGroup(ReplicaTypeEnum.SNAPSHOT.toString(), volume.getLabel());
                }

                volumes.add(volume);
            }
        } else {
            log.info("Snapshot creation for entire Application");
            // get all volumes
            volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);
            // validate that there should be some volumes in VolumeGroup
            if (volumes.isEmpty()) {
                throw APIException.badRequests.replicaOperationNotAllowedOnEmptyVolumeGroup(volumeGroup.getLabel(), ReplicaTypeEnum.SNAPSHOT.toString());
            }
        }

        auditOp(OperationTypeEnum.CREATE_VOLUME_GROUP_SNAPSHOT, true, AuditLogManager.AUDITOP_BEGIN, volumeGroupId.toString(),
                name);
        TaskList taskList = new TaskList();

        Map<URI, List<URI>> cgToVolUris = ControllerUtils.groupVolumeURIsByCG(volumes);
        Set<Entry<URI, List<URI>>> entrySet = cgToVolUris.entrySet();
        for (Entry<URI, List<URI>> entry : entrySet) {
            URI cgUri = entry.getKey();
            log.info("Create snapshot with consistency group {}", cgUri);
            try {
                BlockConsistencyGroupSnapshotCreate cgSnapshotParam = new BlockConsistencyGroupSnapshotCreate(
                        name, entry.getValue(), param.getCreateInactive(), param.getReadOnly());
                TaskList cgTaskList = _blockConsistencyGroupService.createConsistencyGroupSnapshot(cgUri, cgSnapshotParam);
                List<TaskResourceRep> taskResourceRepList = cgTaskList.getTaskList();
                if (taskResourceRepList != null && !taskResourceRepList.isEmpty()) {
                    for (TaskResourceRep taskResRep : taskResourceRepList) {
                        taskList.addTask(taskResRep);
                    }
                }
            } catch (InternalException | APIException e) {
                log.error("Exception when creating snapshot with consistency group {}: {}", cgUri, e.getMessage());
            }
        }

        auditOp(OperationTypeEnum.CREATE_VOLUME_GROUP_SNAPSHOT, true, AuditLogManager.AUDITOP_END, volumeGroupId.toString(),
                name);

        return taskList;
    }

    /**
     * List snapshots for a volume group
     *
     * @prereq none
     * @param volumeGroupId The URI of the volume group.
     * @brief List snapshots for a volume group
     * @return SnapshotList
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public SnapshotList getVolumeGroupSnapshots(@PathParam("id") final URI volumeGroupId) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, ID_FIELD);
        // query volume group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.SNAPSHOT);

        // get all volumes
        List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // get the snapshots for each volume in the group
        SnapshotList snapshotList = new SnapshotList();
        for (Volume volume : volumes) {
            URIQueryResultList snapshotURIs = new URIQueryResultList();
            _dbClient.queryByConstraint(ContainmentConstraint.Factory.getVolumeSnapshotConstraint(
                    volume.getId()), snapshotURIs);
            if (!snapshotURIs.iterator().hasNext()) {
                continue;
            }

            List<BlockSnapshot> snapshots = _dbClient.queryObject(BlockSnapshot.class, snapshotURIs);
            for (BlockSnapshot snapshot : snapshots) {
                if (snapshot != null && !snapshot.getInactive()) {
                    snapshotList.getSnapList().add(toNamedRelatedResource(snapshot));
                }
            }
        }

        return snapshotList;
    }

   /**
     * Get the specified volume group snapshot.
     *
     * @prereq none
     * @param volumeGroupId The URI of the volume group.
     * @param snapshotId The URI of the snapshot.
     * @brief Get the specified volume group snapshot.
     * @return BlockSnapshotRestRep.
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots/{sid}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public BlockSnapshotRestRep getVolumeGroupSnapshot(@PathParam("id") final URI volumeGroupId,
            @PathParam("sid") URI snapshotId) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, ID_FIELD);
        // query Volume Group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.SNAPSHOT);

        // validate snapshot ID
        ArgValidator.checkFieldUriType(snapshotId, BlockSnapshot.class, SNAPSHOT_ID_FIELD);

        // get snapshot
        BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotId);
        ArgValidator.checkEntity(snapshot, snapshotId,isIdEmbeddedInURL(snapshotId), true);

        // validate that source of the provided snapshot is part of the volume group
        Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent());
        if (volume == null || volume.getInactive() || !volume.getVolumeGroupIds().contains(volumeGroupId.toString())) {
            throw APIException.badRequests
                    .replicaOperationNotAllowedVolumeNotInVolumeGroup(ReplicaTypeEnum.SNAPSHOT.toString(), volume.getLabel());
        }

        return BlockMapper.map(_dbClient, snapshot);
    }

    /**
     * Get all snapsetLabels for a volume group
     *
     * @prereq none
     * @param volumeGroupId The URI of the volume group
     * @brief List snapsetLabels for a volume group
     * @return LabelSet
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots/labels")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public LabelSet getVolumeGroupSnapsetLabels(@PathParam("id") final URI volumeGroupId) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, ID_FIELD);
        // query volume group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        return getVolumeGroupSnapsetLabels(volumeGroup);
    }

    /**
     * List snapshots in a snapshot set for a volume group
     *
     * @prereq none
     * @param volumeGroupId The URI of the volume group
     * @brief List snapshots in snapshot set for a volume group
     * @return SnapshotList
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots/labels/{label}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public SnapshotList getVolumeGroupSnapshotsByLabel(@PathParam("id") final URI volumeGroupId, @PathParam("label") String snapsetLabel) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, ID_FIELD);
        // query volume group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.SNAPSHOT);

        // validate snapsetLabel
        ArgValidator.checkFieldNotEmpty(snapsetLabel, LABEL_FIELD);

        // get all volumes
        List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(_dbClient, volumeGroup);

        // get the snapshots for each volume in the group
        SnapshotList snapshotList = new SnapshotList();
        for (Volume volume : volumes) {
            URIQueryResultList snapshotURIs = new URIQueryResultList();
            _dbClient.queryByConstraint(ContainmentConstraint.Factory.getVolumeSnapshotConstraint(
                    volume.getId()), snapshotURIs);
            if (!snapshotURIs.iterator().hasNext()) {
                continue;
            }

            List<BlockSnapshot> snapshots = _dbClient.queryObject(BlockSnapshot.class, snapshotURIs);
            for (BlockSnapshot snapshot : snapshots) {
                if (snapshot != null && !snapshot.getInactive()) {
                    if (snapsetLabel.equals(snapshot.getSnapsetLabel())) {
                        snapshotList.getSnapList().add(toNamedRelatedResource(snapshot));
                    }
                }
            }
        }

        return snapshotList;
    }

    /**
     * Validate resources and group snapshots by snapsetLabel
     *
     * If partial, group snapshots in VolumeGroupSnapshotOperationParam by snapsetLabel
     * If full, find all the snapshots for each snapsetLabel that the snapshots in the param belong to
     *
     * @param volumeGroupId
     * @param param
     * @return map snapsetLabel to snapshots
     */
    private Map<String, List<BlockSnapshot>> getSnapshotsGroupedBySnapset(final URI volumeGroupId, VolumeGroupSnapshotOperationParam param) {
        ArgValidator.checkFieldUriType(volumeGroupId, VolumeGroup.class, "id");
        // Query volume group
        final VolumeGroup volumeGroup = (VolumeGroup) queryResource(volumeGroupId);
        ArgValidator.checkEntityNotNull(volumeGroup, volumeGroupId, isIdEmbeddedInURL(volumeGroupId));

        // validate replica operation for volume group
        validateCopyOperationForVolumeGroup(volumeGroup, ReplicaTypeEnum.SNAPSHOT);

        // validate that at least one snapshot URI is provided
        ArgValidator.checkFieldNotEmpty(param.getSnapshots(), SNAPSHOTS_FIELD);

        Map<String, List<BlockSnapshot>> snapsetToSnapshots = new HashMap<String, List<BlockSnapshot>>();
        for (URI snapshotURI : param.getSnapshots()) {
            ArgValidator.checkFieldUriType(snapshotURI, BlockSnapshot.class, SNAPSHOT_FIELD);
            // Get the snapshot
            BlockSnapshot snapshot = BlockServiceUtils.querySnapshotResource(snapshotURI, uriInfo, _dbClient);

            // validate that source of the provided snapshot is part of the volume group
            Volume volume = _dbClient.queryObject(Volume.class, snapshot.getParent());
            if (volume == null || volume.getInactive() || !volume.getVolumeGroupIds().contains(volumeGroupId.toString())) {
                throw APIException.badRequests
                        .replicaOperationNotAllowedVolumeNotInVolumeGroup(ReplicaTypeEnum.SNAPSHOT.toString(), volume.getLabel());
            }

            String snapsetLabel = snapshot.getSnapsetLabel();
            List<BlockSnapshot> snapshots = snapsetToSnapshots.get(snapsetLabel);
            if (snapshots == null) {
                if (param.getPartial()) {
                    snapshots = new ArrayList<BlockSnapshot>();
                    snapshots.add(snapshot);
                } else {
                    snapshots = ControllerUtils.getVolumeGroupSnapshots(volumeGroup.getId(), snapsetLabel, _dbClient);
                }

                snapsetToSnapshots.put(snapsetLabel, snapshots);
            } else if (param.getPartial()) {
                snapshots.add(snapshot);
            }
        }

        return snapsetToSnapshots;
    }

    /*
     * Wrapper of BlockConsistencyGroupService methods for snapshot operations
     *
     * @param volumeGroupId
     * @param param
     * @return a TaskList
     */
    private TaskList performVolumeGroupSnapshotOperation(final URI volumeGroupId, final VolumeGroupSnapshotOperationParam param, OperationTypeEnum opType) {
        Map<String, List<BlockSnapshot>> snapsetToSnapshots = getSnapshotsGroupedBySnapset(volumeGroupId, param);

        auditOp(opType, true, AuditLogManager.AUDITOP_BEGIN,
                volumeGroupId.toString(), param.getSnapshots());
        TaskList taskList = new TaskList();

        Set<Entry<String, List<BlockSnapshot>>> entrySet = snapsetToSnapshots.entrySet();
        for (Entry<String, List<BlockSnapshot>> entry : entrySet) {
            Table<URI, String, BlockSnapshot> storageRgToSnapshot = ControllerUtils.getSnapshotForStorageReplicationGroup(entry.getValue());
            for (Cell<URI, String, BlockSnapshot> cell : storageRgToSnapshot.cellSet()) {
                log.info("{} for replication group {}", opType.getDescription(), cell.getColumnKey());
                try {
                    BlockSnapshot snapshot = cell.getValue();
                    URI cgUri = snapshot.getConsistencyGroup();
                    URI snapshotUri = snapshot.getId();
                    switch (opType) {
                        case ACTIVATE_VOLUME_GROUP_SNAPSHOT:
                            taskList.addTask(_blockConsistencyGroupService.activateConsistencyGroupSnapshot(cgUri, snapshotUri));
                            break;
                        case RESTORE_VOLUME_GROUP_SNAPSHOT:
                            taskList.addTask(_blockConsistencyGroupService.restoreConsistencyGroupSnapshot(cgUri, snapshotUri));
                            break;
                        case RESYNCHRONIZE_VOLUME_GROUP_SNAPSHOT:
                            taskList.addTask(_blockConsistencyGroupService.resynchronizeConsistencyGroupSnapshot(cgUri, snapshotUri));
                            break;
                        case DEACTIVATE_VOLUME_GROUP_SNAPSHOT:
                            TaskList cgTaskList = _blockConsistencyGroupService.deactivateConsistencyGroupSnapshot(cgUri, snapshotUri);
                            List<TaskResourceRep> taskResourceRepList = cgTaskList.getTaskList();
                            if (taskResourceRepList != null && !taskResourceRepList.isEmpty()) {
                                for (TaskResourceRep taskResRep : taskResourceRepList) {
                                    taskList.addTask(taskResRep);
                                }
                            }
                            break;
                        default:
                            log.error("Unsupported operation {}", opType.getDescription());
                            break;
                    }
                } catch (InternalException | APIException e) {
                    log.error("Exception on {} for replication group {}: {}", opType.getDescription(), cell.getColumnKey(), e.getMessage());
                }
            }
        }

        auditOp(opType, true, AuditLogManager.AUDITOP_END, volumeGroupId.toString(), param.getSnapshots());
        return taskList;
    }

    /**
     * Activate the specified Volume group snapshot.
     * - Activates snapshot for all the array replication groups within this Application.
     * - If partial flag is specified, it activates snapshot only for set of array replication groups.
     * A snapshot from each array replication group can be provided to indicate which array replication
     * groups's snapshots needs to be activated.
     *
     * @prereq Create volume group snapshot.
     *
     * @param volumeGroupId The URI of the volume group.
     * @param param VolumeGroupSnapshotOperationParam.
     *
     * @brief Activate volume group snapshot.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots/activate")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList activateVolumeGroupSnapshot(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupSnapshotOperationParam param) {
        return performVolumeGroupSnapshotOperation(volumeGroupId, param, OperationTypeEnum.ACTIVATE_VOLUME_GROUP_SNAPSHOT);
    }

    /**
     * Deactivate the specified Volume group snapshot.
     * - Deactivates snapshot for all the array replication groups within this Application.
     * - If partial flag is specified, it deactivates snapshot only for set of array replication groups.
     * A snapshot from each array replication group can be provided to indicate which array replication
     * groups's snapshots needs to be deactivated.
     *
     * @prereq Create volume group snapshot.
     *
     * @param volumeGroupId The URI of the volume group.
     * @param param VolumeGroupSnapshotOperationParam.
     *
     * @brief Deactivate volume group snapshot.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots/deactivate")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList deactivateVolumeGroupSnapshot(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupSnapshotOperationParam param) {
        return performVolumeGroupSnapshotOperation(volumeGroupId, param, OperationTypeEnum.DEACTIVATE_VOLUME_GROUP_SNAPSHOT);
    }

    /**
     * Restore the specified Volume group snapshot.
     * - Restores snapshot for all the array replication groups within this Application.
     * - If partial flag is specified, it restores snapshot only for set of array replication groups.
     * A snapshot from each array replication group can be provided to indicate which array replication
     * groups's snapshots needs to be restored.
     *
     * @prereq Create volume group snapshot.
     *
     * @param volumeGroupId The URI of the volume group.
     * @param param VolumeGroupSnapshotOperationParam.
     *
     * @brief Restore volume group snapshot.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots/restore")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList restoreVolumeGroupSnapshot(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupSnapshotOperationParam param) {
        return performVolumeGroupSnapshotOperation(volumeGroupId, param, OperationTypeEnum.RESTORE_VOLUME_GROUP_SNAPSHOT);
    }

    /**
     * Resynchronize the specified Volume group snapshot.
     * - Resynchronizes snapshot for all the array replication groups within this Application.
     * - If partial flag is specified, it resynchronizes snapshot only for set of array replication groups.
     * A snapshot from each array replication group can be provided to indicate which array replication
     * groups's snapshots needs to be resynchronized.
     *
     * @prereq Create volume group snapshot.
     *
     * @param volumeGroupId The URI of the volume group.
     * @param param VolumeGroupSnapshotOperationParam.
     *
     * @brief Resynchronize volume group snapshot.
     *
     * @return TaskList
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}/protection/snapshots/resynchronize")
    @CheckPermission(roles = { Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public TaskList resynchronizeVolumeGroupSnapshot(@PathParam("id") final URI volumeGroupId,
            final VolumeGroupSnapshotOperationParam param) {
        return performVolumeGroupSnapshotOperation(volumeGroupId, param, OperationTypeEnum.RESYNCHRONIZE_VOLUME_GROUP_SNAPSHOT);
    }

    private List<VolumeGroupUtils> getVolumeGroupUtils(VolumeGroup volumeGroup) {
        List<VolumeGroupUtils> utilsList = new ArrayList<VolumeGroupUtils>();

        if (volumeGroup.getRoles().contains(VolumeGroup.VolumeGroupRole.COPY.toString())) {
            utilsList.add(new CopyVolumeGroupUtils());
        }
        if (volumeGroup.getRoles().contains(VolumeGroup.VolumeGroupRole.MOBILITY.toString())) {
            utilsList.add(new MobilityVolumeGroupUtils());
        }
        if (volumeGroup.getRoles().contains(VolumeGroup.VolumeGroupRole.DR.toString())) {
            utilsList.add(new DRVolumeGroupUtils());
        }

        return utilsList;
    }

    private static abstract class VolumeGroupUtils {
        /**
         * @param param
         * @param volumeGroup
         * @param taskId
         * @param taskList
         * @return
         */
        public abstract void updateVolumesInVolumeGroup(DbClient dbClient, final VolumeGroupUpdateParam param, VolumeGroup volumeGroup, String taskId, TaskList taskList);

        /**
         * @param dbClient
         * @param param
         * @param volumeGroup
         * @param taskId
         * @param taskList
         */
        public abstract void validateUpdateVolumesInVolumeGroup(DbClient dbClient, final VolumeGroupUpdateParam param, VolumeGroup volumeGroup);

        protected void updateVolumeObjects(DbClient dbClient, List<Volume> addVols, List<Volume> removeVols, VolumeGroup volumeGroup) {
            for (Volume addVol : addVols) {
                addVol.getVolumeGroupIds().add(volumeGroup.getId().toString());
            }
            for (Volume remVol : removeVols) {
                remVol.getVolumeGroupIds().remove(volumeGroup.getId().toString());
            }
            dbClient.updateObject(addVols);
            dbClient.updateObject(removeVols);
        }

        /**
         * Add task for volumes and consistency groups
         *
         * @param addVols
         * @param removeVols
         * @param removeVolumeCGs
         * @param taskId
         * @param taskList
         */
        protected void addTasksForVolumesAndCGs(DbClient dbClient, List<Volume> addVols, List<Volume> removeVols, Set<URI> removeVolumeCGs,
                String taskId, TaskList taskList) {
            if (addVols != null && !addVols.isEmpty()) {
                for (Volume vol : addVols) {
                    addVolumeTask(dbClient, vol, taskList, taskId, ResourceOperationTypeEnum.UPDATE_VOLUME_GROUP);
                }
            }
            if (removeVols != null && !removeVols.isEmpty()) {
                for (Volume vol : removeVols) {
                    addVolumeTask(dbClient, vol, taskList, taskId, ResourceOperationTypeEnum.UPDATE_VOLUME_GROUP);
                }
            }

            if (removeVolumeCGs != null && !removeVolumeCGs.isEmpty()) {
                for (URI cg : removeVolumeCGs) {
                    addConsistencyGroupTask(dbClient, cg, taskList, taskId, ResourceOperationTypeEnum.UPDATE_VOLUME_GROUP);
                }
            }
        }

        /**
         * @param dbClient
         * @param uriList
         * @param taskId
         * @param e
         */
        protected void updateFailedVolumeTasks(DbClient dbClient, List<URI> uriList, String taskId, ServiceCoded e) {
            for (URI uri : uriList) {
                Volume vol = dbClient.queryObject(Volume.class, uri);
                Operation op = vol.getOpStatus().get(taskId);
                if (op != null) {
                    op.error(e);
                    vol.getOpStatus().updateTaskStatus(taskId, op);
                    dbClient.updateObject(vol);
                }
            }
        }

        /**
         * Creates tasks against consistency group associated with a request and adds them to the given task list.
         *
         * @param group
         * @param taskList
         * @param taskId
         * @param operationTypeEnum
         */
        private void addConsistencyGroupTask(DbClient dbClient, URI groupUri, TaskList taskList,
                String taskId,
                ResourceOperationTypeEnum operationTypeEnum) {
            BlockConsistencyGroup group = dbClient.queryObject(BlockConsistencyGroup.class, groupUri);
            Operation op = dbClient.createTaskOpStatus(BlockConsistencyGroup.class, group.getId(), taskId,
                    operationTypeEnum);
            taskList.getTaskList().add(TaskMapper.toTask(group, taskId, op));
        }

        /**
         * Creates tasks against volume associated with a request and adds them to the given task list.
         *
         * @param volume
         * @param taskList
         * @param taskId
         * @param operationTypeEnum
         */
        private void addVolumeTask(DbClient dbClient, Volume volume, TaskList taskList,
                String taskId,
                ResourceOperationTypeEnum operationTypeEnum) {
            Operation op = dbClient.createTaskOpStatus(Volume.class, volume.getId(), taskId,
                    operationTypeEnum);
            taskList.getTaskList().add(TaskMapper.toTask(volume, taskId, op));
        }
    }

    private static class MobilityVolumeGroupUtils extends VolumeGroupUtils {

        /* (non-Javadoc)
         * @see com.emc.storageos.api.service.impl.resource.VolumeGroupService.VolumeGroupUtils#updateVolumesInVolumeGroup(com.emc.storageos.db.client.DbClient, java.net.URI, com.emc.storageos.model.application.VolumeGroupUpdateParam, com.emc.storageos.db.client.model.VolumeGroup, java.lang.String, com.emc.storageos.model.TaskList)
         */
        @Override
        public void updateVolumesInVolumeGroup(DbClient dbClient, VolumeGroupUpdateParam param, VolumeGroup volumeGroup, String taskId,
                TaskList taskList) {

            List<Volume> removeVols = new ArrayList<Volume>();
            List<Volume> addVols = new ArrayList<Volume>();

            if (param.hasVolumesToAdd()) {
                Iterator<Volume> addVolItr = dbClient.queryIterativeObjects(Volume.class, param.getAddVolumesList().getVolumes());
                while (addVolItr.hasNext()) {
                    addVols.add(addVolItr.next());
                }
            }
            if (param.hasVolumesToRemove()) {
                Iterator<Volume> remVolItr = dbClient.queryIterativeObjects(Volume.class, param.getRemoveVolumesList().getVolumes());
                while (remVolItr.hasNext()) {
                    removeVols.add(remVolItr.next());
                }
            }

            Operation op = dbClient.createTaskOpStatus(VolumeGroup.class, volumeGroup.getId(),
                    taskId, ResourceOperationTypeEnum.UPDATE_VOLUME_GROUP);
            taskList.getTaskList().add(toTask(volumeGroup, taskId, op));
            addTasksForVolumesAndCGs(dbClient, addVols, removeVols, null, taskId, taskList);

            try {
                updateVolumeObjects(dbClient, addVols, removeVols, volumeGroup);
            }  catch (InternalException | APIException e) {
                VolumeGroup app = dbClient.queryObject(VolumeGroup.class, volumeGroup.getId());
                op = app.getOpStatus().get(taskId);
                op.error(e);
                app.getOpStatus().updateTaskStatus(taskId, op);
                dbClient.updateObject(app);
                if (param.hasVolumesToAdd()) {
                    List<URI> addURIs = param.getAddVolumesList().getVolumes();
                    updateFailedVolumeTasks(dbClient, addURIs, taskId, e);
                }
                if (param.hasVolumesToRemove()) {
                    List<URI> removeURIs = param.getRemoveVolumesList().getVolumes();
                    updateFailedVolumeTasks(dbClient, removeURIs, taskId, e);
                }
                throw e;
            }

            updateVolumeAndGroupTasks(dbClient, addVols, removeVols, volumeGroup, taskId);
        }

        /* (non-Javadoc)
         * @see com.emc.storageos.api.service.impl.resource.VolumeGroupService.VolumeGroupUtils#validateUpdateVolumesInVolumeGroup(com.emc.storageos.db.client.DbClient, com.emc.storageos.model.application.VolumeGroupUpdateParam, com.emc.storageos.db.client.model.VolumeGroup)
         */
        @Override
        public void validateUpdateVolumesInVolumeGroup(DbClient dbClient, VolumeGroupUpdateParam param, VolumeGroup volumeGroup) {
            // TODO Auto-generated method stub

        }

        protected void updateVolumeAndGroupTasks(DbClient dbClient, List<Volume> addVols, List<Volume> removeVols, VolumeGroup volumeGroup, String taskId) {
            if (addVols != null && !addVols.isEmpty() ) {
                updateVolumeTasks(dbClient, addVols, taskId);
            }
            if (removeVols != null && !removeVols.isEmpty()) {
                updateVolumeTasks(dbClient, removeVols, taskId);
            }
            Operation op = volumeGroup.getOpStatus().get(taskId);
            op.ready();
            volumeGroup.getOpStatus().updateTaskStatus(taskId, op);
            dbClient.updateObject(volumeGroup);
        }

        protected void updateVolumeTasks(DbClient dbClient, List<Volume> vols, String taskId) {
            for (Volume vol : vols) {
                vol = dbClient.queryObject(Volume.class, vol.getId());
                Operation op = vol.getOpStatus().get(taskId);
                if (op != null) {
                    op.ready();
                    vol.getOpStatus().updateTaskStatus(taskId, op);
                    dbClient.updateObject(vol);
                }
            }
        }

    }

    private static class DRVolumeGroupUtils extends VolumeGroupUtils {

        /* (non-Javadoc)
         * @see com.emc.storageos.api.service.impl.resource.VolumeGroupService.VolumeGroupUtils#updateVolumesInVolumeGroup(com.emc.storageos.db.client.DbClient, java.net.URI, com.emc.storageos.model.application.VolumeGroupUpdateParam, com.emc.storageos.db.client.model.VolumeGroup, java.lang.String, com.emc.storageos.model.TaskList)
         */
        @Override
        public void updateVolumesInVolumeGroup(DbClient dbClient, VolumeGroupUpdateParam param, VolumeGroup volumeGroup, String taskId,
                TaskList taskList) {
            // TODO Auto-generated method stub

        }

        /* (non-Javadoc)
         * @see com.emc.storageos.api.service.impl.resource.VolumeGroupService.VolumeGroupUtils#validateUpdateVolumesInVolumeGroup(com.emc.storageos.db.client.DbClient, com.emc.storageos.model.application.VolumeGroupUpdateParam, com.emc.storageos.db.client.model.VolumeGroup)
         */
        @Override
        public void validateUpdateVolumesInVolumeGroup(DbClient dbClient, VolumeGroupUpdateParam param, VolumeGroup volumeGroup) {
            // TODO Auto-generated method stub

        }

    }

    private static class CopyVolumeGroupUtils extends VolumeGroupUtils {

        private List<Volume> removeVols;
        private List<Volume> addVols;
        private Set<URI> impactedCGs = new HashSet<URI>();
        private Volume firstVol;
        private boolean validated;

        /* (non-Javadoc)
         * @see com.emc.storageos.api.service.impl.resource.VolumeGroupService.VolumeGroupUtils#validateUpdateVolumesInVolumeGroup(com.emc.storageos.db.client.DbClient, com.emc.storageos.model.application.VolumeGroupUpdateParam, com.emc.storageos.db.client.model.VolumeGroup, java.lang.String, com.emc.storageos.model.TaskList)
         */
        @Override
        public void validateUpdateVolumesInVolumeGroup(DbClient dbClient, VolumeGroupUpdateParam param, VolumeGroup volumeGroup) {
            impactedCGs = new HashSet<URI>();

            if (param.hasVolumesToAdd()) {
                addVols = validateAddVolumes(dbClient, param, volumeGroup, impactedCGs);
                firstVol = addVols.get(0);
            }
            if (param.hasVolumesToRemove()) {
                List<URI> removeVolList = param.getRemoveVolumesList().getVolumes();
                removeVols = validateRemoveVolumes(dbClient, removeVolList, volumeGroup, impactedCGs);
                if (!removeVols.isEmpty() && firstVol == null) {
                    firstVol = removeVols.get(0);
                }
            }
            validated = true;
        }

        /* (non-Javadoc)
         * @see com.emc.storageos.api.service.impl.resource.VolumeGroupService.VolumeGroupUtils#updateVolumesInVolumeGroup(java.net.URI, com.emc.storageos.model.application.VolumeGroupUpdateParam, com.emc.storageos.db.client.model.VolumeGroup, java.lang.String, com.emc.storageos.model.TaskList)
         */
        @Override
        public void updateVolumesInVolumeGroup(DbClient dbClient, VolumeGroupUpdateParam param, VolumeGroup volumeGroup, String taskId, TaskList taskList) {

            if (!validated) {
                validateUpdateVolumesInVolumeGroup(dbClient, param, volumeGroup);
            }

            if (removeVols != null && !removeVols.isEmpty()) {
                // if any of the remove volumes are not in a CG, just update the database
                // this shouldn't happen but it will add robustness if anything goes wrong
                List<Volume> checkVols = new ArrayList<Volume>(removeVols);
                removeVols.clear();
                for (Volume removeVol : checkVols) {
                    if (NullColumnValueGetter.isNullURI(removeVol.getConsistencyGroup())) {
                        removeVol.getVolumeGroupIds().remove(volumeGroup.getId().toString());
                        dbClient.updateObject(removeVol);
                    } else {
                        removeVols.add(removeVol);
                    }
                }
            }

            if ((addVols == null || addVols.isEmpty()) && (removeVols == null || removeVols.isEmpty())) {
                // no volumes to add or remove
                return;
            }

            BlockServiceApi serviceAPI = getBlockService(dbClient, firstVol);
            Operation op = dbClient.createTaskOpStatus(VolumeGroup.class, volumeGroup.getId(),
                    taskId, ResourceOperationTypeEnum.UPDATE_VOLUME_GROUP);
            try {
                taskList.getTaskList().add(toTask(volumeGroup, taskId, op));
                addTasksForVolumesAndCGs(dbClient, addVols, removeVols, impactedCGs, taskId, taskList);
                serviceAPI.updateVolumesInVolumeGroup(param.getAddVolumesList(), removeVols, volumeGroup.getId(), taskId);
            }  catch (InternalException | APIException e) {
                VolumeGroup app = dbClient.queryObject(VolumeGroup.class, volumeGroup.getId());
                op = app.getOpStatus().get(taskId);
                op.error(e);
                app.getOpStatus().updateTaskStatus(taskId, op);
                dbClient.updateObject(app);
                if (param.hasVolumesToAdd()) {
                    List<URI> addURIs = param.getAddVolumesList().getVolumes();
                    updateFailedVolumeTasks(dbClient, addURIs, taskId, e);
                }
                if (param.hasVolumesToRemove()) {
                    List<URI> removeURIs = param.getRemoveVolumesList().getVolumes();
                    updateFailedVolumeTasks(dbClient, removeURIs, taskId, e);
                }
                if (!impactedCGs.isEmpty()) {
                    updateFailedCGTasks(dbClient, impactedCGs, taskId, e);
                }
                throw e;
            }
        }
        
        /**
         * gets the list of replication group names associated with this COPY type volume group
         * @return list of replication group names or empty list if the volume group is not COPY or no volumes exist in 
         * the volume group
         */
        public static Set<String> getReplicationGroupNames(VolumeGroup group, DbClient dbClient) {
            
            Set<String> groupNames = new HashSet<String>();
            if (group.getRoles().contains(VolumeGroup.VolumeGroupRole.COPY.toString())){
                List<Volume> volumes = ControllerUtils.getVolumeGroupVolumes(dbClient, group);
                if (volumes != null && !volumes.isEmpty()) {
                    BlockServiceApi serviceAPI = getBlockService(dbClient, volumes.iterator().next());
                    groupNames.addAll(serviceAPI.getReplicationGroupNames(group));
                }
            }
            return groupNames;
        }

        /**
         * Validate the volumes to be added to the volume group.
         * For role COPY:
         * All volumes should be the same type (block, or RP, or VPLEX, or SRDF), and should be in consistency groups
         *          *
         * @param volumes
         * @return The validated volumes
         */
        private List<Volume> validateAddVolumes(DbClient dbClient, VolumeGroupUpdateParam param, VolumeGroup volumeGroup, Set<URI> impactedCGs) {
            String addedVolType = null;
            String firstVolLabel = null;
            List<URI> addVolList = param.getAddVolumesList().getVolumes();
            List<Volume> volumes = new ArrayList<Volume>();
            for (URI volUri : addVolList) {
                ArgValidator.checkFieldUriType(volUri, Volume.class, "id");
                Volume volume = dbClient.queryObject(Volume.class, volUri);
                if (volume == null || volume.getInactive()) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(), "the volume has been deleted");
                }

                URI cgUri = volume.getConsistencyGroup();
                if (NullColumnValueGetter.isNullURI(cgUri)) {
                     throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                             "Volume is not in a consistency group");
                }

                // check mirrors
                StringSet mirrors = volume.getMirrors();
                if (mirrors != null && !mirrors.isEmpty()) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                            "Volume has mirror");
                }

                URI systemUri = volume.getStorageController();
                StorageSystem system = dbClient.queryObject(StorageSystem.class, systemUri);
                String type = system.getSystemType();
                if (!ALLOWED_SYSTEM_TYPES.contains(type)) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                            "The storage system type that the volume created in is not allowed ");
                }
                String volType = getVolumeType(type);
                if (addedVolType == null) {
                    addedVolType = volType;
                    firstVolLabel = volume.getLabel();
                }
                if (!volType.equals(addedVolType)) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                            "The volume type is not same as others");
                }

                // check to make sure this volume is not part of another application
                StringSet volumeGroups = volume.getVolumeGroupIds();
                List<String> badVolumeGroups = new ArrayList<String>();
                if (volumeGroups != null && !volumeGroups.isEmpty()) {
                    for (String vgId : volumeGroups) {
                        VolumeGroup vg = dbClient.queryObject(VolumeGroup.class, URI.create(vgId));
                        if (vg == null || vg.getInactive()) {
                            // this means the volume points to a non-existent volume group;
                            // this shouldn't happen but we can clean this dangling reference up
                            badVolumeGroups.add(vgId);
                        } else {
                            if (vg.getRoles().contains(VolumeGroup.VolumeGroupRole.COPY.toString())) {
                                throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volume.getLabel(),
                                        String.format("The volume is already a member of an application: %s", vg.getLabel()));
                            }
                        }
                    }
                    if (!badVolumeGroups.isEmpty()) {
                        for (String vgId : badVolumeGroups) {
                            volume.getVolumeGroupIds().remove(vgId);
                        }
                        dbClient.updateObject(volume);
                        volume = dbClient.queryObject(Volume.class, volume.getId());
                    }
                }
                volumes.add(volume);
                impactedCGs.add(volume.getConsistencyGroup());
            }
            // Check if the to-add volumes are the same volume type as existing volumes in the application
            List<Volume> existingVols = ControllerUtils.getVolumeGroupVolumes(dbClient, volumeGroup);
            if (!existingVols.isEmpty()) {
                Volume firstVolume = existingVols.get(0);
                URI systemUri = firstVolume.getStorageController();
                StorageSystem system = dbClient.queryObject(StorageSystem.class, systemUri);
                String type = system.getSystemType();
                String existingType = getVolumeType(type);
                if (!existingType.equals(addedVolType)) {
                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(firstVolLabel,
                            "The volume type is not same as existing volumes in the application");
                }
            }
            
            // Check to make sure the replication group name is not used in a CG that is not part of an application
            // or part of another application
            // Check to make sure to be added volumes are in the same CG if the backend volumes are in the same backend array
            // All volumes in the same replication group should belong to the same CG.
            if (param.getAddVolumesList().getReplicationGroupName() != null) {
                String replicationGroupName = param.getAddVolumesList().getReplicationGroupName();
                List<Volume> volumesInReplicationGroup = CustomQueryUtility.queryActiveResourcesByConstraint(
                        dbClient, Volume.class, AlternateIdConstraint.Factory.getVolumeByReplicationGroupInstance(replicationGroupName));
                List<URI>toAddVolumes = param.getAddVolumesList().getVolumes();
                // Get the backend volumes not in replication group and sort them in storage system and CG
                Map<URI, URI> backendVolSystemCGMap = new HashMap<URI, URI>();
                for (URI volUri : toAddVolumes) {
                    Volume volToAdd = dbClient.queryObject(Volume.class, volUri);
                    URI cgURI = volToAdd.getConsistencyGroup();
                    StringSet backendVols = volToAdd.getAssociatedVolumes();
                    if (backendVols != null && !backendVols.isEmpty()) {
                        for (String backendUri : backendVols) {
                            Volume backendVol = dbClient.queryObject(Volume.class, URI.create(backendUri));
                            if (backendVol != null && NullColumnValueGetter.isNullValue(backendVol.getReplicationGroupInstance())) {
                                URI storage = backendVol.getStorageController();
                                URI sortCG = backendVolSystemCGMap.get(storage);
                                if (sortCG != null && !cgURI.equals(sortCG)) {
                                    // there are at least two volumes backend volumes are from the same storage system, 
                                    // but their CGs are different, throw error
                                    throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(volToAdd.getLabel(),
                                            "the volumes in the request are from different consistency group, they could not be added into the same replication group.");
                                } else if (sortCG == null) {
                                    backendVolSystemCGMap.put(storage, cgURI);
                                }
                            }
                        }
                    }
                }
                if (volumesInReplicationGroup != null && !volumesInReplicationGroup.isEmpty()) {
                    for (Volume volumeInRepGrp : volumesInReplicationGroup) {
                        URI storage = volumeInRepGrp.getStorageController();
                        URI addingCG = backendVolSystemCGMap.get(storage);
                        if (addingCG != null) {
                            URI existingVolCG = volumeInRepGrp.getConsistencyGroup();
                            if (!addingCG.equals(existingVolCG)) {
                                throw APIException.badRequests.volumeCantBeAddedToVolumeGroup(firstVolLabel,
                                        String.format("the replication group %s is existing, but the volumes in the request are from different consistency group", replicationGroupName));
                            }
                        
                            Volume volToCheck = volumeInRepGrp;
                            
                            // if this is a vplex backing volume, get the parent virtual voume
                            if (VPlexUtil.isVplexBackendVolume(volumeInRepGrp, dbClient)) {
                                List<Volume> vplexVolumes = CustomQueryUtility.queryActiveResourcesByConstraint(dbClient, Volume.class,
                                                getVolumesByAssociatedId(volumeInRepGrp.getId().toString()));
                                if (vplexVolumes != null && !vplexVolumes.isEmpty()) {
                                    // we expect just one parent virtual volume for each backing volume
                                    volToCheck = vplexVolumes.get(0);
                                }
                            }
                                
                            // check to see if the volume is part of another application or not part of an application
                            VolumeGroup grp = volToCheck.getApplication(dbClient);
                            if (grp == null) {
                                throw APIException.badRequests.volumeGroupCantBeUpdated(volumeGroup.getLabel(),
                                        String.format("a volume, %s is part of the volume group %s but is not part of any application", volToCheck.getLabel(), replicationGroupName));
                            } else if (!grp.getId().equals(volumeGroup.getId())) {
                                throw APIException.badRequests.volumeGroupCantBeUpdated(volumeGroup.getLabel(),
                                        String.format("a volume, %s is part of the volume group %s and is part of another application: %s", volToCheck.getLabel(), replicationGroupName, grp.getLabel()));
                            }
                        }
                    }
                }
            }
            
            return volumes;
        }

        /**
         * Valid the volumes to be removed from the volume group. Called by updateVolumeGroup()
         *
         * @param volumes the volumes to be removed from volume group
         * @param volumeGroup The volume group
         * @return The validated volumes
         */
        private List<Volume> validateRemoveVolumes(DbClient dbClient, List<URI> volumes, VolumeGroup volumeGroup, Set<URI> removeVolumeCGs) {
            List<Volume> removeVolumes = new ArrayList<Volume>();
            for (URI voluri : volumes) {
                ArgValidator.checkFieldUriType(voluri, Volume.class, "id");
                Volume vol = dbClient.queryObject(Volume.class, voluri);
                if (vol == null || vol.getInactive()) {
                    log.warn(String.format("The volume [%s] will not be removed from application %s because it does not exist or has been deleted", voluri.toString(), volumeGroup.getLabel()));
                    continue;
                }
                StringSet volumeGroups = vol.getVolumeGroupIds();
                if (volumeGroups == null || !volumeGroups.contains(volumeGroup.getId().toString())) {
                    log.warn(String.format("The volume %s will not be removed from application %s because it is not assigned to the application", vol.getLabel(), volumeGroup.getLabel()));
                    continue;
                }

                if (!NullColumnValueGetter.isNullURI(vol.getConsistencyGroup()) && !isVPlexVolume(vol, dbClient)) {
                    removeVolumeCGs.add(vol.getConsistencyGroup());
                }

                removeVolumes.add(vol);
            }
            return removeVolumes;
        }

        /**
         * Get Volume type, either block, rp, vplex or srdf
         *
         * @param type The system type
         * @return
         */
        private static String getVolumeType(String type) {
            if (BLOCK_TYPES.contains(type)) {
                return BLOCK;
            } else {
                return type;
            }
        }

        private void updateFailedCGTasks(DbClient dbClient, Set<URI> uriList, String taskId, ServiceCoded e) {
            for (URI uri : uriList) {
                BlockConsistencyGroup cg = dbClient.queryObject(BlockConsistencyGroup.class, uri);
                Operation op = cg.getOpStatus().get(taskId);
                if (op != null) {
                    op.error(e);
                    cg.getOpStatus().updateTaskStatus(taskId, op);
                    dbClient.updateObject(cg);
                }
            }
        }
        
        private static BlockServiceApi getBlockService(DbClient dbClient, final Volume volume) {
            if (!isNullURI(volume.getProtectionController())
                    && volume.checkForRp()) {
                return getBlockServiceImpl(DiscoveredDataObject.Type.rp.name());
            }

            if (Volume.checkForSRDF(dbClient, volume.getId())) {
                return getBlockServiceImpl(DiscoveredDataObject.Type.srdf.name());
            }

            URI systemUri = volume.getStorageController();
            StorageSystem system = dbClient.queryObject(StorageSystem.class, systemUri);
            String type = system.getSystemType();
            String volType = getVolumeType(type);
            return getBlockServiceImpl(volType);
        }   
        
    }

    /**
     * get the children for this volume group
     *
     * @param dbClient
     *            db client for db queries
     * @param volumeGroup
     *            volume group to get children for
     * @return a list of volume groups
     */
    private static List<VolumeGroup> getVolumeGroupChildren(DbClient dbClient, VolumeGroup volumeGroup) {
        List<VolumeGroup> result = new ArrayList<VolumeGroup>();
        final List<VolumeGroup> volumeGroups = CustomQueryUtility.queryActiveResourcesByConstraint(dbClient, VolumeGroup.class,
                ContainmentConstraint.Factory.getVolumesGroupsByVolumeGroupId(volumeGroup.getId()));
        for (VolumeGroup volGroup : volumeGroups) {
            result.add(volGroup);
        }
        return result;
    }

    /**
     * Check if the application has any pending task
     * 
     * @param application
     */
    private void checkForApplicationPendingTasks(VolumeGroup volumeGroup) {
        List<Task> newTasks = TaskUtils.findResourceTasks(_dbClient, volumeGroup.getId());
        for (Task task : newTasks) {
            if (task != null && !task.getInactive() && task.isPending()) {
                throw APIException.badRequests.cannotExecuteOperationWhilePendingTask(volumeGroup.getLabel());
            }
        }
    }

    private String setParent(VolumeGroup volumeGroup, String parent) {
        String errorMsg = null;
        // add parent if specified
        if (parent != null && !parent.isEmpty()) {
            if (URIUtil.isValid(parent)) {
                URI parentId = URI.create(parent);
                ArgValidator.checkFieldUriType(parentId, VolumeGroup.class, "parent");
                VolumeGroup parentVG = _dbClient.queryObject(VolumeGroup.class, parentId);
                if (parentVG == null || parentVG.getInactive()) {
                    errorMsg = "The parent volume group does not exist";
                } else {
                    volumeGroup.setParent(parentId);
                }
            } else if (NullColumnValueGetter.isNullValue(parent)) {
                volumeGroup.setParent(NullColumnValueGetter.getNullURI());
            } else {
                List<VolumeGroup> parentVg = CustomQueryUtility
                        .queryActiveResourcesByConstraint(_dbClient, VolumeGroup.class,
                                PrefixConstraint.Factory.getLabelPrefixConstraint(VolumeGroup.class, parent));
                if (parentVg == null || parentVg.isEmpty()) {
                    errorMsg = "The parent volume group does not exist";
                } else {
                    volumeGroup.setParent(parentVg.iterator().next().getId());
                }
            }
        }
        return errorMsg;
    }

    /**
     * Check if the volume is a vplex volume
     * @param volume The volume to be checked
     * @return true or false
     */
    static private boolean isVPlexVolume(Volume volume, DbClient dbClient) {
        boolean result = false;
        URI storageUri = volume.getStorageController();
        StorageSystem storage = dbClient.queryObject(StorageSystem.class, storageUri);
        String systemType = storage.getSystemType();
        if (systemType.equals(DiscoveredDataObject.Type.vplex.name())) {
            result = true;
        }
        return result;
    }
}
