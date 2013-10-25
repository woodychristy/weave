/*
 * Copyright 2012-2013 Continuuity,Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.internal.json;

import com.continuuity.weave.api.WeaveRunResources;
import com.continuuity.weave.internal.DefaultWeaveRunResources;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

/**
 * Codec for serializing and deserializing a {@link WeaveRunResources} object using json.
 */
public final class WeaveRunResourcesCodec implements JsonSerializer<WeaveRunResources>,
                                              JsonDeserializer<WeaveRunResources> {

  @Override
  public JsonElement serialize(WeaveRunResources src, Type typeOfSrc, JsonSerializationContext context) {
    JsonObject json = new JsonObject();

    json.addProperty("containerId", src.getContainerId());
    json.addProperty("instanceId", src.getInstanceId());
    json.addProperty("host", src.getHost());
    json.addProperty("memoryMB", src.getMemoryMB());
    json.addProperty("virtualCores", src.getVirtualCores());

    return json;
  }

  @Override
  public WeaveRunResources deserialize(JsonElement json, Type typeOfT,
                                           JsonDeserializationContext context) throws JsonParseException {
    JsonObject jsonObj = json.getAsJsonObject();
    return new DefaultWeaveRunResources(jsonObj.get("instanceId").getAsInt(),
                                        jsonObj.get("containerId").getAsString(),
                                        jsonObj.get("virtualCores").getAsInt(),
                                        jsonObj.get("memoryMB").getAsInt(),
                                        jsonObj.get("host").getAsString());
  }
}
