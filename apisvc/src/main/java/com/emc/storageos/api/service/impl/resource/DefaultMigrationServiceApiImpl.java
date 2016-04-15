/*
 * Copyright (c) 2016 Intel Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.placement.StorageScheduler;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyUtils;
import com.emc.storageos.api.service.impl.resource.snapshot.BlockSnapshotSessionUtils;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.migrationorchestrationcontroller.MigrationOrchestrationController;
import com.emc.storageos.migrationorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.svcs.errorhandling.resources.APIException;

/*
 * Default implementation of the Migration Service Api.
 */
public class DefaultMigrationServiceApiImpl extends AbstractMigrationServiceApiImpl<StorageScheduler> {
    private static final Logger s_logger = LoggerFactory
            .getLogger(MigrationBlockServiceApiImpl.class);

    // The max number of volumes allowed in a CG for varray and vpool
    // changes resulting in backend data migrations. Set in the API
    // service configuration file.
    private int _maxCgVolumesForMigration;

    private static final String MIGRATION_LABEL_SUFFIX = "m";

    /**
     * Public setter for Spring configuration.
     *
     * @param maxCgVols Max number of volumes allowed in a CG for varray and
     *            vpool changes resulting in backend data migrations
     */
    public void setMaxCgVolumesForMigration(int maxCgVols) {
        _maxCgVolumesForMigration = maxCgVols;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifyVarrayChangeSupportedForVolumeAndVarray(Volume volume,
            VirtualArray newVarray) throws APIException {

        // The volume cannot be exported
        if (volume.isVolumeExported(_dbClient)) {
            s_logger.info("The volume is exported.");
            throw APIException.badRequests.changesNotSupportedFor("VirtualArray", "exported volumes");
        }

        // If the vpool has assigned varrays, the vpool must be assigned
        // to the new varray.
        VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        StringSet vpoolVarrayIds = vpool.getVirtualArrays();
        if ((vpoolVarrayIds == null) || (!vpoolVarrayIds.contains(newVarray.getId().toString()))) {
            throw APIException.badRequests.vpoolNotAssignedToVarrayForVarrayChange(
                    vpool.getLabel(), volume.getLabel());
        }

        // The volume must be detached from all full copies.
        if (BlockFullCopyUtils.volumeHasFullCopySession(volume, _dbClient)) {
            throw APIException.badRequests.volumeForVarrayChangeHasFullCopies(volume.getLabel());
        }

    }

    /**
     * Verifies if the valid storage pools for the target vpool specify a single
     * target storage system.
     *
     * @param currentVPool The source vpool for a vpool change
     * @param newVPool The target vpool for a vpool change.
     * @param srcVarrayURI The virtual array for the volumes being migrated.
     */
    private void verifyTargetSystemsForCGDataMigration(List<Volume> volumes,
            VirtualPool newVPool, URI srcVarrayURI) {
        // Determine if the vpool change requires a migration. If the
        // new vpool is null, then this is a varray migration and the
        // varray is changing. In either case, the valid storage pools
        // specified in the target vpool that are tagged to the passed
        // varray must specify the same system so that all volumes are
        // placed on the same array.
        URI tgtSystemURI = null;
        URI tgtHASystemURI = null;
        for (Volume volume : volumes) {
            VirtualPool currentVPool = _dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
            if ((newVPool == null) || (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentVPool, newVPool))) {
                VirtualPool vPoolToCheck = (newVPool == null ? currentVPool : newVPool);
                List<StoragePool> pools = VirtualPool.getValidStoragePools(vPoolToCheck, _dbClient, true);
                for (StoragePool pool : pools) {
                    // We only need to check the storage pools in the target vpool that
                    // are tagged to the virtual array for the volumes being migrated.
                    if (!pool.getTaggedVirtualArrays().contains(srcVarrayURI.toString())) {
                        continue;
                    }

                    if (tgtSystemURI == null) {
                        tgtSystemURI = pool.getStorageDevice();
                    } else if (!tgtSystemURI.equals(pool.getStorageDevice())) {
                        throw APIException.badRequests.targetVPoolDoesNotSpecifyUniqueSystem();
                    }
                }
            }

            // The same restriction applies to the target HA virtual pool
            // when the HA side is being migrated.
            URI haVArrayURI = VirtualPoolChangeAnalyzer.getHaVarrayURI(currentVPool, _dbClient);
            if (!NullColumnValueGetter.isNullURI(haVArrayURI)) {
                // The HA varray is not null so must be distributed.
                VirtualPool currentHAVpool = VirtualPoolChangeAnalyzer.getHaVpool(currentVPool, _dbClient);
                VirtualPool newHAVpool = VirtualPoolChangeAnalyzer.getNewHaVpool(currentVPool, newVPool, _dbClient);
                if (VirtualPoolChangeAnalyzer.vpoolChangeRequiresMigration(currentHAVpool, newHAVpool)) {
                    List<StoragePool> haPools = VirtualPool.getValidStoragePools(newHAVpool, _dbClient, true);
                    for (StoragePool haPool : haPools) {
                        // We only need to check the storage pools in the target HA vpool
                        // tagged to the HA virtual array for the volumes being migrated.
                        if (!haPool.getTaggedVirtualArrays().contains(haVArrayURI.toString())) {
                            continue;
                        }

                        if (tgtHASystemURI == null) {
                            tgtHASystemURI = haPool.getStorageDevice();
                        } else if (!tgtHASystemURI.equals(haPool.getStorageDevice())) {
                            throw APIException.badRequests.targetHAVPoolDoesNotSpecifyUniqueSystem();
                        }
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void migrateVolumesVirtualArray(List<Volume> volumes,
            BlockConsistencyGroup cg, List<Volume> cgVolumes, VirtualArray tgtVarray,
            boolean driverMigration, String taskId) throws InternalException {

        // Since the backend volume would change and snapshots are just
        // snapshots of the backend volume, the user would lose all snapshots
        // if we allowed the varray change. Therefore, we don't allow the varray
        // change if the volume has snapshots. The user would have to explicitly
        // go and delete their snapshots first.
        //
        // Note: We make this validation here instead of in the verification
        // method "verifyVarrayChangeSupportedForVolumeAndVarray" because the
        // verification method is called from not only the API to change the
        // volume virtual array, but also the API that determines the volumes
        // that would be eligible for a proposed varray change. The latter API
        // is used by the UI to populate the list of volumes. We want volumes
        // with snaps to appear in the list, so that the user will know that
        // if they remove the snapshots, they can perform the varray change.
        for (Volume volume : volumes) {
            List<BlockSnapshot> snapshots = getSnapshots(volume);
            if (!snapshots.isEmpty()) {
                for (BlockSnapshot snapshot : snapshots) {
                    if (!snapshot.getInactive()) {
                        throw APIException.badRequests.volumeForVarrayChangeHasSnaps(volume.getId().toString());
                    }
                }
            }
            // If the volume has mirrors then varray change will not
            // be allowed. User needs to explicitly delete mirrors first.
            StringSet mirrorURIs = volume.getMirrors();
            if (mirrorURIs != null && !mirrorURIs.isEmpty()) {
                List<BlockMirror> mirrors = _dbClient.queryObject(BlockMirror.class, StringSetUtil.stringSetToUriList(mirrorURIs));
                if (mirrors != null && !mirrors.isEmpty()) {
                    throw APIException.badRequests
                        .volumeForVarrayChangeHasMirrors(volume.getId().toString(), volume.getLabel());
                }
            }
        }

        // All volumes will be migrated in the same workflow.
        // If there are many volumes in the CG, the workflow
        // will have many steps. Worse, if an error occurs
        // additional workflow steps get added for rollback.
        // An issue can then arise trying to persist the
        // workflow to Zoo Keeper because the workflow
        // persistence is restricted to 250000 bytes. So
        // we add a restriction on the number of volumes in
        // the CG that will be allowed for a data migration
        if ((cg != null) && (volumes.size() > _maxCgVolumesForMigration)) {
            throw APIException.badRequests
                    .cgContainsTooManyVolumesForVArrayChange(cg.getLabel(),
                            volumes.size(), _maxCgVolumesForMigration);
        }

        // Varray changes for volumes in CGs will always migrate all
        // volumes in the CG. If there are multiple volumes in the CG
        // and the CG has associated backend CGs then we need to ensure
        // that when the migration targets are created for these volumes
        // they are placed on the same backend storage system in the new
        // varray. Therefore, we ensure this is the case by verifying that
        // the valid storage pools for the volume's vpool that are tagged
        // to the new varray resolve to a single storage system. If not
        // we don't allow the varray change.
        if ((cg != null) && (cg.checkForType(Types.LOCAL)) && (cgVolumes.size() > 1)) {
            verifyTargetSystemsForCGDataMigration(volumes, null, tgtVarray.getId());
        }

        // Create the volume descriptors for the virtual array change.
        List<VolumeDescriptor> descriptors = createVolumeDescriptorsForVarrayChange(
                volumes, tgtVarray, taskId);

        try {
            // Orchestrate the virtual array change.
            MigrationOrchestrationController controller = getController(
                    MigrationOrchestrationController.class,
                    MigrationOrchestrationController.MIGRATION_ORCHESTRATION_DEVICE);
            controller.changeVirtualArray(descriptors, taskId);
            s_logger.info("Successfully invoked migration orchestrator.");
        } catch (InternalException e) {
            s_logger.error("Controller error", e);
            for (VolumeDescriptor descriptor : descriptors) {
                // Make sure to clean up the tasks associated with the
                // migration targets and migrations.
                if (VolumeDescriptor.Type.MIGRATE_VOLUME.equals(descriptor.getType())) {
                    _dbClient.error(Volume.class, descriptor.getVolumeURI(), taskId, e);
                    _dbClient.error(Migration.class, descriptor.getMigrationId(), taskId, e);
                }
            }
            throw e;
        }

    }

    /**
     * Creates the volumes descriptors for a varray change for the passed
     * list of volumes.
     *
     * @param volumes The volumes being moved.
     * @param tgtVarray The target virtual array.
     * @param driverMigration Boolean describing whether or not this will be a driver assisted migration.
     * @param taskId The task identifier.
     *
     * @return A list of volume descriptors
     */
    private List<VolumeDescriptor> createVolumeDescriptorsForVarrayChange(List<Volume> volumes,
            VirtualArray tgtVarray, boolean driverMigration, String taskId) {

        // The list of descriptors for the virtual array change.
        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        // The storage system.
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                volumes.get(0).getStorageController());

        // Create a descriptor for each volume.
        for (Volume volume : volumes) {
            VolumeDescriptor descriptor = new VolumeDescriptor(
                    VolumeDescriptor.Type.BLOCK_DATA, volume.getStorageController(),
                    volume.getId(), null, null);
            Map<String, Object> descrParams = new HashMap<String, Object>();
            descrParams.put(VolumeDescriptor.PARAM_VARRAY_CHANGE_NEW_VAARAY_ID, newVarray.getId());
            descriptor.setParameters(descrParams);
            descriptors.add(descriptor);

            // We'll need to prepare a target volume and create a
            // descriptor for each backend volume being migrated.
            StringSet assocVolumes = volume.getAssociatedVolumes();
            String assocVolumeId = assocVolumes.iterator().next();
            URI assocVolumeURI = URI.create(assocVolumeId);
            Volume assocVolume = _dbClient.queryObject(Volume.class, assocVolumeURI);
            VirtualPool assocVolumeVPool = _dbClient.queryObject(VirtualPool.class,
                    assocVolume.getVirtualPool());
            descriptors.addAll(createBackendVolumeMigrationDescriptors(storageSystem,
                    volume, assocVolume, tgtVarray, assocVolumeVPool,
                    getVolumeCapacity(assocVolume), driverMigration,
                    taskId, null, null));
        }

        return descriptors;
    }

    /**
     * Does the work necessary to prepare the passed backend volume for the
     * passed virtual volume to be migrated to a new volume with a new VirtualPool.
     *
     * @param storageSystem A reference to the storage system.
     * @param virtualVolume A reference to the virtual volume.
     * @param sourceVolume A reference to the backend volume to be migrated.
     * @param varray A reference to the varray for the backend volume.
     * @param vpool A reference to the VirtualPool for the new volume.
     * @param capacity The capacity for the migration target.
     * @param driverMigration Boolean describing whether or not this will be a driver assisted migration.
     * @param taskId The task identifier.
     * @param newVolumes An OUT parameter to which the new volume is added.
     * @param migrationMap A OUT parameter to which the new migration is added.
     * @param poolVolumeMap An OUT parameter associating the new Volume to the
     *            storage pool in which it will be created.
     */
    private List<VolumeDescriptor> createBackendVolumeMigrationDescriptors(StorageSystem storageSystem,
            Volume virtualVolume, Volume sourceVolume, VirtualArray varray, VirtualPool vpool,
            Long capacity, String taskId, List<Recommendation> recommendations,
            VirtualPoolCapabilityValuesWrapper capabilities) {

        URI sourceVolumeURI = null;
        Project targetProject = null;
        String targetLabel = null;
        // Ideally we would just give the new backend volume the same
        // name as the current i.e., source. However, this is a problem
        // if the migration is on the same array. We can't have two volumes
        // with the same name. Eventually the source will go away, but not
        // until after the migration is completed. The backend volume names
        // are basically irrelevant, but we still want them tied to
        // the volume name.
        //
        // The volume have an additional suffix of "-<1...N>" if the
        // volume was created as part of a multi-volume creation
        // request, where N was the number of volumes requested. When
        // a volume is first migrated, we will append a "m" to the current
        // source volume name to ensure name uniqueness. If the volume
        // happens to be migrated again, we'll remove the extra character.
        // We'll go back forth in this manner for each migration of that
        // backend volume.
        sourceVolumeURI = sourceVolume.getId();
        targetProject = _dbClient.queryObject(Project.class, sourceVolume.getProject().getURI());
        targetLabel = sourceVolume.getLabel();
        if (!targetLabel.endsWith(MIGRATION_LABEL_SUFFIX)) {
            targetLabel += MIGRATION_LABEL_SUFFIX;
        } else {
            targetLabel = targetLabel.substring(0, targetLabel.length() - 1);
        }

        // Get the recommendation for this volume placement.
        Set<URI> requestedStorageSystems = new HashSet<URI>();
        requestedStorageSystems.add(storageSystem.getId());

        URI cgURI = null;
        // Check to see if the VirtualPoolCapabilityValuesWrapper have been passed in, if not, create a new one.
        if (capabilities != null) {
            // The consistency group or null when not specified.
            final BlockConsistencyGroup consistencyGroup = capabilities.getBlockConsistencyGroup() == null ? null : _dbClient
                    .queryObject(BlockConsistencyGroup.class, capabilities.getBlockConsistencyGroup());

            // If the consistency group is created but does not specify the LOCAL
            // type, the CG must be a CG created prior to 2.2 or an ingested CG. In
            // this case, we don't want a volume creation to result in backend CGs
            if ((consistencyGroup != null) && ((!consistencyGroup.created()) ||
                    (consistencyGroup.getTypes().contains(Types.LOCAL.toString())))) {
                cgURI = consistencyGroup.getId();
            }
        } else {
            capabilities = new VirtualPoolCapabilityValuesWrapper();
            capabilities.put(VirtualPoolCapabilityValuesWrapper.SIZE, capacity);
            capabilities.put(VirtualPoolCapabilityValuesWrapper.RESOURCE_COUNT, new Integer(1));
        }

        boolean premadeRecs = false;

        if (recommendations == null || recommendations.isEmpty()) {
            recommendations = getBlockScheduler().scheduleStorage(
                    varray, requestedStorageSystems, null, vpool, false, null, null, capabilities);
            if (recommendations.isEmpty()) {
                throw APIException.badRequests.noStorageFoundForVolumeMigration(vpool.getLabel(), varray.getLabel(), sourceVolumeURI);
            }
            s_logger.info("Got recommendation");
        } else {
            premadeRecs = true;
        }

        // If we have premade recommendations passed in and this is trying to create descriptors for HA
        // then the HA rec will be at index 1 instead of index 0. Default case is index 0.
        int recIndex = (premadeRecs && isHA) ? 1 : 0;

        // Create a volume for the new backend volume to which
        // data will be migrated.
        URI targetStorageSystem = recommendations.get(recIndex).getSourceStorageSystem();
        URI targetStoragePool = recommendations.get(recIndex).getSourceStoragePool();
        Volume targetVolume = prepareVolumeForRequest(capacity,
                targetProject, varray, vpool, targetStorageSystem, targetStoragePool,
                targetLabel, ResourceOperationTypeEnum.CREATE_BLOCK_VOLUME,
                taskId, _dbClient);

        // If the cgURI is null, try and get it from the source volume.
        if (cgURI == null) {
            if ((sourceVolume != null) && (!NullColumnValueGetter.isNullURI(sourceVolume.getConsistencyGroup()))) {
                cgURI = sourceVolume.getConsistencyGroup();
                targetVolume.setConsistencyGroup(cgURI);
            }
        }

        if ((sourceVolume != null) && NullColumnValueGetter.isNotNullValue(sourceVolume.getReplicationGroupInstance())) {
            targetVolume.setReplicationGroupInstance(sourceVolume.getReplicationGroupInstance());
        }

        targetVolume.addInternalFlags(Flag.INTERNAL_OBJECT);
        _dbClient.updateObject(targetVolume);

        s_logger.info("Prepared volume {}", targetVolume.getId());

        // Add the volume to the passed new volumes list and pool
        // volume map.
        URI targetVolumeURI = targetVolume.getId();

        List<VolumeDescriptor> descriptors = new ArrayList<VolumeDescriptor>();

        descriptors.add(new VolumeDescriptor(VolumeDescriptor.Type.BLOCK_DATA,
                targetStorageSystem,
                targetVolumeURI,
                targetStoragePool,
                cgURI,
                capabilities,
                capacity));

        // Create a migration to represent the migration of data
        // from the backend volume to the new backend volume for the
        // passed virtual volume and add the migration to the passed
        // migrations list.
        Migration migration = prepareMigration(virtualVolume.getId(),
                sourceVolumeURI, targetVolumeURI, taskId);

        descriptors.add(new VolumeDescriptor(VolumeDescriptor.Type.MIGRATE_VOLUME,
                targetStorageSystem,
                targetVolumeURI,
                targetStoragePool,
                cgURI,
                migration.getId(),
                capabilities));

        s_logger.info("Prepared migration {}.", migration.getId());

        return descriptors;
    }

}
