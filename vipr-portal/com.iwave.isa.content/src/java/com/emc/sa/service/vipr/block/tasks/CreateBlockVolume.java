/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.block.tasks;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import com.emc.sa.service.vipr.tasks.WaitForTasks;
import com.emc.storageos.model.block.VolumeCreate;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.vipr.client.Tasks;

public class CreateBlockVolume extends WaitForTasks<VolumeRestRep> {
    private URI vpoolId;
    private URI varrayId;
    private URI projectId;
    private String size;
    private Integer count;
    private String name;
    private URI consistencyGroupId;
    private URI computeResource;
    private URI rdfGroup;
    private URI portGroup;

    public CreateBlockVolume(String vpoolId, String varrayId, String projectId, String size, Integer count,
            String name, String consistencyGroupId, URI rdfGroup) {
        this(uri(vpoolId), uri(varrayId), uri(projectId), size, count, name, uri(consistencyGroupId), null, null, rdfGroup);
    }

    public CreateBlockVolume(URI vpoolId, URI varrayId, URI projectId, String size, Integer count, String name,
            URI consistencyGroupId, URI computeResource, URI portGroup, URI rdfGroup) {
        this.vpoolId = vpoolId;
        this.varrayId = varrayId;
        this.projectId = projectId;
        this.size = size;
        this.count = count;
        this.name = name;
        this.consistencyGroupId = consistencyGroupId;
        this.computeResource = computeResource;
        this.rdfGroup = rdfGroup;
        this.portGroup = portGroup;
        provideDetailArgs(name, size, vpoolId, varrayId, projectId);
    }

    @Override
    public Tasks<VolumeRestRep> doExecute() throws Exception {
        VolumeCreate create = new VolumeCreate();
        create.setVpool(vpoolId);
        create.setVarray(varrayId);
        create.setProject(projectId);
        create.setName(name);
        create.setSize(size);
        int numberOfVolumes = 1;
        if ((count != null) && (count > 1)) {
            numberOfVolumes = count;
        }
        create.setCount(numberOfVolumes);
        create.setConsistencyGroup(consistencyGroupId);
        if (computeResource != null) {
            create.setComputeResource(computeResource);
        }
        create.setExtensionParams(null);
        if (portGroup != null) {
            create.setPortGroup(portGroup);
        }
        
        if (rdfGroup != null) {
            Set<String> extensionParams = new HashSet<>();
            extensionParams.add(String.format("%s=%s", VolumeCreate.EXTENSION_PARAM_KNOWN_RDFGROUP, rdfGroup));
            create.setExtensionParams(extensionParams);
        }

        Tasks<VolumeRestRep> tasks = getClient().blockVolumes().create(create);
        // There should only be as many tasks as is the count
        if (tasks.getTasks().size() != numberOfVolumes) {
            throw stateException("CreateBlockVolume.illegalState.invalid", tasks.getTasks().size());
        }
        return tasks;
    }
}
