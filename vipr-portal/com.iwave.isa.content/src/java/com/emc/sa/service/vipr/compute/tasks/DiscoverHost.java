/*
 * Copyright (c) 2017 Dell EMC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.compute.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.ViPRExecutionTask;
import com.emc.storageos.model.host.HostRestRep;
import com.emc.vipr.client.Task;

public class DiscoverHost extends ViPRExecutionTask<Task<HostRestRep>> {

    private URI hostId;

    public DiscoverHost(URI hostID) {
        this.hostId = hostID;
        provideDetailArgs(hostID);
    }

    @Override
    public Task<HostRestRep> executeTask() throws Exception {
        Task<HostRestRep> task = getClient().hosts().discover(hostId);
        addOrderIdTag(task.getTaskResource().getId());
        return task;
    }
}
