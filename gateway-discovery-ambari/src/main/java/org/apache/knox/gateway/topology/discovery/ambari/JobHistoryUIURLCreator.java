/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.knox.gateway.topology.discovery.ambari;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A ServiceURLCreator implementation for JOBHISTORYUI (MapReduce2's JobHistoryServer web UI).
 * mapreduce.jobhistory.webapp.address is already a combined host:port value, so it can be used
 * directly as the URL authority, the same way HDFSURLCreatorBase treats dfs.namenode.http-address.
 */
public class JobHistoryUIURLCreator implements ServiceURLCreator {

  private static final String SERVICE = "JOBHISTORYUI";

  private static final String COMPONENT = "HISTORYSERVER";

  private static final String ADDRESS_PROPERTY = "mapreduce.jobhistory.webapp.address";

  private AmbariCluster cluster;

  @Override
  public void init(AmbariCluster cluster) {
    this.cluster = cluster;
  }

  @Override
  public String getTargetService() {
    return SERVICE;
  }

  @Override
  public List<String> create(String service, Map<String, String> serviceParams) {
    List<String> urls = new ArrayList<>();

    if (getTargetService().equalsIgnoreCase(service)) {
      AmbariComponent comp = cluster.getComponent(COMPONENT);
      if (comp != null) {
        String address = comp.getConfigProperty(ADDRESS_PROPERTY);
        if (address != null && !address.isEmpty()) {
          urls.add("http://" + address);
        }
      }
    }

    return urls;
  }

}
