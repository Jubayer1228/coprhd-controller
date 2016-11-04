/*
 * Copyright 2016 Dell Inc. or its subsidiaries.
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
 *
 */
package com.emc.storageos.model.orchestration.internal;


/**
 *  Base class for a primitive that represents a ViPR API call
 */
public abstract class ViPRPrimitive extends Primitive {
    
    public ViPRPrimitive(String name, String friendlyName, String description,
            String successCriteria, InputParameter[] input,
            OutputParameter[] output) {
        super(name, friendlyName, description, successCriteria, input, output);
    }

    public abstract String path();
    public abstract String method();
    
}
