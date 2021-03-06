/*
 * Copyright 2016 Intel Corporation
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

package com.emc.storageos.keystone.restapi.model.request;

import com.emc.storageos.keystone.restapi.model.response.EndpointV2;

/**
 * Keystone API endpoint create request class.
 * Use this class once you obtained a token from Keystone.
 */
public class CreateEndpointRequest {

    private EndpointV2 endpoint;

    public EndpointV2 getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(EndpointV2 endpoint) {
        this.endpoint = endpoint;
    }
}
