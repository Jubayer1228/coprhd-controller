/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.workflow;

import com.emc.storageos.svcs.errorhandling.annotations.DeclareServiceCode;
import com.emc.storageos.svcs.errorhandling.annotations.MessageBundle;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;

@MessageBundle
public interface WorkflowExceptions {

    @DeclareServiceCode(ServiceCode.WORKFLOW_NOT_FOUND)
    public WorkflowException workflowNotFound(String id);

    @DeclareServiceCode(ServiceCode.WORKFLOW_IN_WRONG_STATE)
    public WorkflowException workflowRollbackInWrongState(String id, String expectedState, String actualState);

    @DeclareServiceCode(ServiceCode.WORKFLOW_CANNOT_BE_ROLLED_BACK)
    public WorkflowException workflowRollbackNotInitiated(String uri);

    @DeclareServiceCode(ServiceCode.WORKFLOW_CANNOT_BE_ROLLED_BACK)
    public WorkflowException innerWorkflowRollbackError(String uri, String messages);
    
    @DeclareServiceCode(ServiceCode.WORKFLOW_TERMINATED_DR_FAILOVER)
    public WorkflowException workflowTerminatedForFailover(String uri);

    @DeclareServiceCode(ServiceCode.WORKFLOW_INVALID_ARGUMENTS)
    public WorkflowException workflowSuspendTriggerInvalidNull();

    @DeclareServiceCode(ServiceCode.WORKFLOW_INVALID_ARGUMENTS)
    public WorkflowException workflowSuspendTriggerInvalid(String name);

    @DeclareServiceCode(ServiceCode.WORKFLOW_INVALID_ARGUMENTS)
    public WorkflowException workflowSuspendTriggerNotFound(String classMethodName, String knownEntries);
    
    @DeclareServiceCode(ServiceCode.WORKFLOW_CONSTRUCTION_ERROR)
    public WorkflowException workflowConstructionError(String reason);

    @DeclareServiceCode(ServiceCode.WORKFLOW_IN_WRONG_STATE)
    public WorkflowException workflowStepInTerminalState(String stepId, String state, String newState);

    @DeclareServiceCode(ServiceCode.WORKFLOW_IN_WRONG_STATE)
    public WorkflowException workflowNotSuspended(final String string, final String state);
}
