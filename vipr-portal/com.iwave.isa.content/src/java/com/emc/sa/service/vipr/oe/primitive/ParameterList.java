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
package com.emc.sa.service.vipr.oe.primitive;

import java.util.Map;

public class ParameterList extends AbstractParameter<Map<String, Parameter>> {

    private static final long serialVersionUID = 1L;

    Map<String, Parameter> _parameters;

    public ParameterList() {

    }

    @Override
    public Map<String, Parameter> getValue() {
        return _parameters;
    }

    @Override
    public void setValue(final Map<String, Parameter> map) {
        _parameters = map;
    }

    @Override
    public boolean isParameterList() {
        return true;
    }

    @Override
    public ParameterList asParameterList() {
        // TODO Auto-generated method stub
        return this;
    }

    @Override
    public boolean isParameter() {
        return false;
    }

    @Override
    public Parameter asParameter() {
        return null;
    }

}
